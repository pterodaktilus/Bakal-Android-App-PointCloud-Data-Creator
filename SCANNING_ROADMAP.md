# Interior scanning: feasibility and implementation roadmap

Reviewed: 2026-10-25.

- Capture device: Google Pixel 8 Pro.
- First milestone: one room with good visual completeness.
- Reconstruction: an external computer/server; hardware and reconstruction engine remain undecided.
- Android dependency reviewed: ARCore 1.56.0, including its locally available API source documentation.
- Assessment basis: repository inspection and official documentation. Capture quality, synchronization, performance, and reconstruction have not been validated on the phone/server in this review.

## 1. Objective and feasibility

Build an Android app that displays a live camera feed with accumulated sparse geometry, helps the user identify weakly observed areas while scanning, and exports camera keyframes with calibration and pose information for later dense reconstruction.

This is a feasible prototype goal for the Pixel 8 Pro, which Google lists as supporting the Depth API. Exact capture resolutions and compatible stream combinations must still be enumerated and tested on the device. A guarantee that every room surface will reconstruct accurately is outside what the available evidence establishes. [Google supported-device list](https://developers.google.com/ar/devices)

The phone should produce a documented capture dataset. Its format must remain useful if the current desktop pipeline is replaced. COLMAP is a possible baseline, not a requirement of the capture architecture.

The first complete milestone requires:

- A camera-aligned sparse overlay that preserves earlier room observations.
- Useful RGB keyframes with matching calibration and estimated poses.
- Separate, exportable scan sessions with observable recording failures.
- A dataset that can be independently checked and reconstructed on another computer.
- Repeatable evidence that the preview helps the user recognize weak areas.

A live video feed does not require recording an MP4. Continuous video recording can be evaluated separately; the first reconstruction dataset can consist of selected image files and metadata.

## 2. Current implementation: observed state

These findings describe the working tree reviewed on the date above. Line references may move as implementation proceeds.

| Requirement | Current state | Evidence and consequence |
| --- | --- | --- |
| Camera feed on screen | Missing from the active rendering path | [RawDepthActivity.java](app/src/main/java/com/google/ar/core/examples/java/rawdepth/RawDepthActivity.java#L295) passes camera texture ID `0` and never draws a camera background. |
| Sparse depth points | Implemented | [PointCloudHelper.java](app/src/main/java/com/google/ar/core/examples/java/rawdepth/PointCloudHelper.java#L72) visits every depth pixel and skips zero depth. Uniform subsampling is disabled. |
| Camera-aligned overlay | Requires changes | [Renderer.java](app/src/main/java/com/google/ar/core/examples/java/rawdepth/Renderer.java#L153) applies an artificial one-metre camera displacement. |
| Room-wide observation history | Missing | [Renderer.java](app/src/main/java/com/google/ar/core/examples/java/rawdepth/Renderer.java#L132) retains only the newest 60 depth frames. |
| RGB keyframe archive | Missing | [DepthData.java](app/src/main/java/com/google/ar/core/examples/java/rawdepth/DepthData.java#L56) acquires RGB images to colour points, then releases them without saving the images. |
| Calibration and pose archive | Partial | [DataSave.java](app/src/main/java/com/google/ar/core/examples/java/rawdepth/DataSave.java#L134) records full raw depth/confidence, texture intrinsics, a camera pose, and depth/frame timestamps. It does not record an RGB image or its CPU-image intrinsics. |
| Reliable recording completion | Incomplete | [DataSave.java](app/src/main/java/com/google/ar/core/examples/java/rawdepth/DataSave.java#L28) has four pending queue slots. Rejection and I/O errors are logged; the caller ignores rejection, and shutdown does not wait for completion. |
| Separate scans | Missing | [RawDepthActivity.java](app/src/main/java/com/google/ar/core/examples/java/rawdepth/RawDepthActivity.java#L90) uses a fixed `capture-test.jsonl` path. Writes append, without session identifiers separating unrelated recordings and coordinate systems. |
| Capture controls | Missing | [activity_main.xml](app/src/main/res/layout/activity_main.xml) contains a surface and confidence slider, without Start/Stop/Export controls. |
| Desktop reconstruction | Candidate implementation exists | The sibling `../Desktop/colmap/` code contains reconstruction stages, but `main.py` currently skips them and starts meshing an existing cloud. It does not import the Android raw-depth log. |

The existing [BackgroundRenderer.java](app/src/main/java/com/google/ar/core/examples/java/common/rendering/BackgroundRenderer.java#L161) can supply the camera background. No replacement rendering framework is required merely to add that background.

## 3. Design principles and limitations

### 3.1 Sparse geometry is evidence, not a completeness certificate

Raw depth has missing measurements, particularly on textureless surfaces. A blank wall may have no confident depth despite being visible in many images. Smoothing/interpolation can produce a fuller depth image, but does not establish that every resulting value is an independent observation. [ARCore Raw Depth](https://developers.google.com/ar/develop/java/depth/raw-depth)

A useful guidance system must distinguish:

- A surface with confident depth observations.
- A surface photographed successfully from useful, different viewpoints.
- An area for which the system has insufficient evidence.

Repeated samples from almost the same position should not be treated as strong multi-view coverage. Geometry behind furniture or outside the captured views can remain unknown. Avoid claiming a percentage of the entire room unless its denominator and assumptions are explicitly defined.

The raw-depth cloud also differs from a feature-based Structure-from-Motion (SfM) model: the current exported points do not contain cross-image feature tracks. Their existence alone does not provide everything an image reconstruction engine needs.

### 3.2 Preview and archive have separate fidelity policies

The archive can preserve all pixels of each selected depth record while the preview merges points and limits rendering cost. Removing preview samples must not implicitly remove source images or measurements from the archive.

Disabling subsampling solves spatial skipping within a depth frame. It does not solve temporal retention, image quality, recording reliability, or reconstruction completeness.

### 3.3 Poses need an explicit reference strategy

ARCore can revise camera and anchor coordinates as its understanding changes. Anchors can preserve live spatial relationships; capture-time pose matrices already written to disk do not update themselves. Treat exported camera poses as estimates, and retain enough information to evaluate or refine them offline. [ARCore Pose documentation](https://developers.google.com/ar/reference/java/com/google/ar/core/Pose)

For the live map, use a small set of suitable local references/anchors and map chunks rather than assuming one fixed global coordinate system will remain exact. Retain the relationship between a chunk's geometry and its reference, so tracking updates can move or rebuild that chunk coherently.

For the archive, identify sessions and coordinate/reference conventions. If an implementation exports reference updates or revised poses, give those revisions explicit identities. Anchors alone do not provide a guaranteed globally optimized trajectory, and their in-memory identities are not portable persistent identifiers for another run of the app.

### 3.4 Timestamp freshness and geometric alignment are different

Raw depth can be reprojected to the current camera pose between new depth estimates. A repeated depth timestamp can therefore accompany a depth raster aligned to a later frame. Preserve the depth timestamp, the associated current frame timestamp, and the pose/calibration used for that raster. Do not pair a reprojected raster with an old pose merely because its depth timestamp is old. [ARCore Frame API](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame)

Record the timestamp domains and associations used. Do not assume ARCore frame timestamps, Android camera timestamps, and wall-clock time are interchangeable. If Camera2 stills are introduced, correlate their capture metadata to ARCore explicitly rather than using callback-arrival or button-press time.

### 3.5 Calibration belongs to the exact stored image

For an RGB image obtained through `Frame.acquireCameraImage()`, use `Camera.getImageIntrinsics()`. The existing texture intrinsics describe the GPU texture; preserve them separately where needed to interpret the depth grid. `Camera.getPose()` describes the physical camera, while rendering view/projection matrices incorporate display geometry. [ARCore Camera API](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera)

Snapshot calibration with each selected frame because its values may change after a session update. Document any image rotation, crop, resize, or distortion treatment. Do not assume zero distortion, one immutable calibration, or identical CPU/texture/still-image geometry without validation. [CameraIntrinsics API](https://developers.google.com/ar/reference/java/com/google/ar/core/CameraIntrinsics)

## 4. Proposed capture contract

This is a proposed contract, not an implemented file format or a commitment to a reconstruction engine.

```text
scans/<scan-id>/
├── session.json
├── keyframes.jsonl
├── depth-frames.jsonl
├── images/
│   ├── rgb-000001.jpg
│   └── ...
├── depth/
│   ├── depth-000001.bin
│   └── ...
├── confidence/
│   ├── depth-000001.bin
│   └── ...
└── events.jsonl
```

RGB and depth have independent record identifiers. A filename containing `000001` in each directory does not establish synchronization. Metadata must describe associations, and an RGB keyframe may legitimately have no available associated depth record.

| Record | Information to define and preserve |
| --- | --- |
| Session | Schema version, unique scan/session ID, device and software versions, camera configuration, units, coordinate/reference conventions, recording policy, and completion status. |
| RGB keyframe | Unique ID, relative image path, image/frame timestamps, camera ID/configuration, stored dimensions, intrinsics, image transformations/distortion assumptions, pose estimate and reference, and tracking/quality information. |
| Depth frame | Unique ID, relative depth/confidence paths, associated frame ID/timestamp, source depth timestamp, dimensions, calibration/mapping information, pose/reference for the returned raster, and whether the measurement timestamp is new. |
| Camera metadata | Available exposure/focus information and the provenance of calibration; unavailable fields must be distinguishable from numeric zero. |
| Events and completion | Tracking interruptions, intentionally skipped candidates, queue rejection, write failures, reference changes where recorded, and selected/committed/failed/pending record counts. |

For the current 16-bit depth path, a possible packed representation is unsigned 16-bit little-endian axial depth in millimetres, with zero meaning missing, plus unsigned 8-bit confidence. Explicitly document whether buffers were repacked to contiguous rows. Arbitrary `.bin` files are not self-describing. This representation follows the current API's units; it must not be confused with radial distance along a viewing ray. [Depth values](https://developers.google.com/ar/develop/java/depth/developer-guide#understand-depth-values)

For RGB, begin by preserving the camera-image orientation and choosing an explicit JPEG quality policy. Lossless images or raw YUV can be useful for controlled comparisons but carry different storage costs. A JPEG produced from an ARCore CPU frame retains that frame's available detail; encoding it does not increase capture resolution.

Name transforms explicitly, such as `world_from_camera`, and document axes, handedness, units, quaternion ordering if used, and matrix storage order. Document the world/reference revision to which an estimate belongs. Keep physical camera coordinates separate from display-oriented rendering coordinates.

## 5. Implementation roadmap

All checklist items below are future work or validation gates. They are not claims of completed functionality.

### Phase 1: establish a usable Pixel capture configuration

- [ ] Define preliminary pass/fail criteria for visual completeness, geometric error, scan duration, and recording reliability; refine the targets after the first measured experiment.
- [ ] Enumerate supported ARCore camera configurations: camera ID, CPU-image size, GPU-texture size, frame rate, and compatible depth support.
- [ ] Log the chosen configuration and actual delivered RGB/depth dimensions and timestamps.
- [ ] Measure usable image sharpness while stationary and while moving under typical indoor lighting.
- [ ] Choose an explicit focus policy and assess its effect on sharpness and tracking. The current autofocus comment does not correspond to a `setFocusMode()` call.
- [ ] Keep one camera/lens configuration for the first implementation; record any later configuration changes.
- [ ] Measure sustained performance and storage throughput before selecting a capture rate.

ARCore commonly supplies a lower-resolution default CPU image alongside a higher-resolution preview texture. Higher-resolution CPU configurations are device dependent and can add processing cost. Select from the configurations actually reported by the phone. [ARCore CPU image configuration](https://developers.google.com/ar/develop/java/machine-learning#configure_an_additional_high_resolution_cpu_image_stream)

Completion criterion: a short device experiment identifies a practical configuration with acceptable image quality, tracking, and sustained performance. Exact supported resolutions remain unverified until this experiment runs.

### Phase 2: make the basic camera/depth preview correct

- [ ] Initialize the existing camera background renderer and provide its valid texture ID to ARCore.
- [ ] Draw the camera feed before the cloud; continue showing the feed when depth is temporarily unavailable or tracking is paused, while suppressing invalid geometry updates.
- [ ] Remove the artificial one-metre virtual-camera displacement.
- [ ] Render points with a contrasting highlight colour and an adjustable confidence threshold.
- [ ] Validate depth-to-preview alignment, crop handling, and display orientation.
- [ ] Check buffer layout consistency, including the current three-float colour data versus four-component attribute binding.
- [ ] Verify depth reads against row/pixel strides instead of relying on an untested packed-row assumption.

Completion criterion: nearby and farther physical features remain aligned with the point overlay during translation and rotation. A point-only view or a stationary screenshot is insufficient evidence.

### Phase 3: implement reliable scan sessions and image recording

- [ ] Add Start, Stop, and Export controls and a unique directory for each scan.
- [ ] Specify the versioned capture contract before coupling it to a desktop importer.
- [ ] Start with manual RGB keyframes to establish correct synchronization and file output.
- [ ] Snapshot selected image data, intrinsics, pose, and tracking state while their frame is current.
- [ ] Release acquired images promptly. Pass owned/copied data and immutable metadata to workers rather than reading a `Frame` or mutable `Camera` object after a later `Session.update()`.
- [ ] Encode images and serialize/write large data in background work. The current full-array JSON construction runs synchronously on the render path before the write is queued.
- [ ] Preserve full raw-depth/confidence records according to an explicit selection policy, independently of preview filtering.
- [ ] Track queue rejection and write completion, and show failures to the user.
- [ ] Apply a defined overload policy. Intentionally reduced capture and failed selected captures must be distinguishable; no unbounded queue.
- [ ] Publish successful keyframe records only after their required files have been written successfully; handle incomplete temporary files and interrupted sessions.
- [ ] On Stop, drain pending work and finalize the manifest before reporting recording completion.
- [ ] Export an independently readable folder or archive using an appropriate Android export workflow.

For selected records, a useful accounting invariant is `selected = committed + failed + pending`. Candidates intentionally skipped before selection need their own count. A keyframe with an optional missing depth measurement can still be a valid committed RGB record; define required components in the schema.

Completion criterion: every record reported as committed has its required readable files and matching metadata; failures are visible; a normally completed session has no pending work. A clean Stop result does not establish durability under arbitrary power loss, which requires its own policy and testing.

### Phase 4: select useful keyframes and prove dataset geometry

- [ ] Add automatic selection based on translation/view change, overlap, sharpness, tracking state, and a measured capture-rate limit.
- [ ] Preserve useful overlap and translational baseline; avoid accepting mostly identical images or relying on pure rotation.
- [ ] Keep RGB selection independent of the arrival of a new raw-depth timestamp.
- [ ] Validate paths, dimensions, identifiers, calibration, pose formatting, and cross-record associations in an importer independent of the eventual reconstruction engine.
- [ ] Implement numerical projection/unprojection tests with explicit axes, units, and pixel-coordinate conventions.
- [ ] Check real cross-frame correspondences and overlapping depth observations, including a measured object or distance.
- [ ] Run a small offline reconstruction experiment before investing in sophisticated coverage scoring or high-resolution still integration.

Projecting an unprojected point back into its own image tests internal arithmetic consistency. It cannot independently prove that the chosen calibration, pose, or timestamp association matches the real camera: the same wrong assumptions can cancel out. Use cross-frame geometry and physical references as independent checks.

An engine adapter must convert coordinate conventions deliberately. For example, COLMAP stores world-to-camera poses and uses a different camera-axis convention from ARCore. That is an importer responsibility, not a reason to change the archive to an undocumented engine-specific format. [COLMAP image pose format](https://colmap.github.io/format.html#images-txt)

Completion criterion: a small exported sequence has plausible cameras, independently checked alignment and scale, and supports a useful offline reconstruction experiment. No dense engine is permanently selected by this gate.

### Phase 5: preserve room observations with a bounded live map

- [ ] Replace the 60-frame history as the room-coverage representation with a spatially accumulated map.
- [ ] Merge nearby observations, initially using a simple voxel/grid representation if appropriate.
- [ ] Associate map chunks with local references/anchors and retain enough provenance to move or rebuild them when tracking estimates change.
- [ ] Define explicit budgets for resident points, GPU buffers, references/anchors, and update work per frame.
- [ ] If a budget is reached, coarsen or page chunks while retaining a persistent coverage summary; do not silently forget a scanned wall.
- [ ] Update changed GPU data instead of uploading every historical frame on every draw.
- [ ] Start with persistent highlighted geometry and manual gap inspection before adding automated completeness claims.
- [ ] Tie any photo-coverage indication to committed keyframes, useful viewing differences, image quality, and geometric evidence.
- [ ] Consider visibility and occlusion so historical geometry behind another surface does not falsely suggest good coverage of the visible surface.

Voxel merging reduces duplicate observations but does not by itself bound memory: the number of occupied cells can grow as new space is explored. A budget and a fallback policy are still necessary. Coarse summaries are preview representations; the archive retains its separately defined fidelity.

Completion criterion: returning to the first wall after scanning the room still shows prior observations, with responsive rendering and measured memory usage. Unknown regions remain identifiable as uncertain rather than automatically labelled missed or complete.

### Phase 6: evaluate the offline reconstruction approach

| Approach | Purpose | Limitation to evaluate |
| --- | --- | --- |
| Image-based SfM and multi-view stereo | Recover/refine geometry from overlapping RGB images; useful first baseline. | Texture, sharpness, overlap, calibration, and pose recovery determine the result. |
| Depth accumulation/fusion | Combine exported depth observations into an initial model or debugging cloud. | More samples can improve coverage, but unmeasured surfaces can remain missing; this does not guarantee dense geometry. |
| Hybrid reconstruction | Combine image evidence with depth and pose estimates. | Greater integration effort and a need to handle uncertainty, alignment, and tracking corrections consistently. |

- [ ] Evaluate a baseline using the exported images and calibration, then compare its trajectory and geometry with the recorded ARCore estimates.
- [ ] Decide whether ARCore poses are best used for initialization, constraints, alignment/scale checks, or only diagnostics for the chosen engine.
- [ ] Allow refinement where the evidence warrants it; do not freeze uncertain ARCore poses merely to avoid pose estimation.
- [ ] Keep importer, dataset validation, and reconstruction stages separate so the backend can change.
- [ ] Record engine versions, parameters, hardware, and input dataset identity for reproducibility.
- [ ] Select server hardware after choosing and measuring the reconstruction workload.
- [ ] Produce and inspect a dense coloured point cloud before tuning mesh generation and texturing.
- [ ] Measure registered-image coverage, disconnected components, geometric alignment, scale, and missing surfaces.

Image-based reconstruction normally uses SfM to establish camera geometry and multi-view stereo to recover denser structure. COLMAP supports this workflow and a known-pose route, making it one possible comparison engine. Neither route establishes that every observed room surface is reconstructible. [COLMAP tutorial](https://colmap.github.io/tutorial), [Known-pose reconstruction](https://colmap.github.io/faq.html#reconstruct-sparse-dense-model-from-known-camera-poses)

Current desktop notes, relevant only if that implementation is retained: reconstruction calls are commented out in `../Desktop/colmap/main.py`; `config.py` limits reconstruction image size to 1000 and uses reduced stereo settings; the Dockerfile clones COLMAP without pinning a revision. These are observed starting conditions, not requirements for a future backend.

Completion criterion: an exported scan folder can be processed independently into a useful room point cloud, with documented limitations and a repeatable processing configuration.

### Phase 7: validate the complete one-room workflow

- [ ] Scan a room while deliberately omitting an area, and assess whether the preview helps recognize the omission.
- [ ] Include walls, floor, ceiling, and objects viewed from different positions where those surfaces are accessible.
- [ ] Revisit the starting area and inspect tracking/map alignment after completing the loop.
- [ ] Test a textureless wall, reflections, low light, moving objects, and repeated-looking surfaces as distinct failure cases.
- [ ] Test tracking loss, background/resume behavior, interrupted recording, storage pressure, and export completeness.
- [ ] Monitor frame times, memory, thermals, write backlog, and capture failures for the intended scan duration.
- [ ] Evaluate visual completeness and measured geometric error against the acceptance criteria established during Phase 1.
- [ ] Repeat the same scan/processing procedure to establish whether results are reproducible.

Completion criterion: the phone can repeatedly capture, export, and support reconstruction of one room, with understandable feedback about uncertain areas and recording failures. Scaling to multiple rooms or a building requires a separate evaluation of drift, reference management, relocalization, and scan alignment.

## 6. Higher-resolution still photos: a later, separate experiment

First test the highest practical ARCore CPU-image configuration with matching frame metadata. Do not infer saved-image quality from the preview resolution, and do not assume JPEG encoding creates more captured detail.

If that quality is insufficient, evaluate Camera2 shared access for occasional still images:

- [ ] Verify the Pixel's simultaneous tracking, preview, and still-capture stream combinations.
- [ ] Match the still's actual sensor timestamp/capture metadata to the appropriate pose estimate; do not assign the pose from when the shutter button was pressed.
- [ ] Establish calibration for that still stream, including crop, orientation, distortion treatment, and relevant camera configuration.
- [ ] Measure tracking disruption, latency, blur, encoding/write load, and depth behavior during still capture.
- [ ] Compare reconstruction quality against the CPU-frame baseline before adopting the added complexity.

Google documents possible simultaneous JPEG still capture on compatible configurations, but the example combinations are not a Pixel-specific guarantee. Shared-camera mode prevents ARCore from using a hardware depth sensor even if one is present; that does not mean all depth-from-motion is unavailable. Verify the chosen depth configuration in that experiment. [Shared camera guide](https://developers.google.com/ar/develop/java/camera-sharing), [SharedCamera limitations](https://developers.google.com/ar/reference/java/com/google/ar/core/SharedCamera)

An independent still-capture path should not be assumed to reproduce the stock Pixel Camera app's full processing pipeline or image quality without comparison.

## 7. Logic review and remaining uncertainty

The roadmap is internally consistent if the capture contract is established before backend-specific integration, geometric correctness is tested before coverage claims, and a small offline proof precedes elaborate scan guidance.

The second review tightened these points from the conversational analysis:

1. Persistent preview geometry requires explicit reference/update handling; an accumulated list of frozen world coordinates is insufficient.
2. A depth timestamp identifies freshness, but reprojected data must be associated with its current geometric alignment.
3. Voxel merging needs an explicit memory/rendering budget and a policy that preserves earlier coverage information.
4. A same-image projection round trip cannot independently validate real calibration or synchronization.
5. The first offline reconstruction test belongs before investment in advanced coverage scoring or still-photo integration.
6. Missing depth, missing photographs, weak viewing geometry, and unknown surfaces must remain distinguishable.
7. Successful queue admission is different from successful file output; recording completion must be based on committed data.
8. The archive and its validation remain independent of the existing desktop implementation.

Unknown until measured: supported high-resolution configurations on the actual phone; acceptable capture rate and scan duration; focus/sharpness behavior; timestamp associations for a future Camera2 path; room-scale drift; achievable completeness on weakly textured surfaces; server performance; and the most suitable reconstruction engine.

First implementation target: a correctly aligned live preview and a small, exportable sequence of synchronized, calibrated RGB keyframes with verifiable metadata. Persistent room mapping and complete room reconstruction follow the validation gates above.
