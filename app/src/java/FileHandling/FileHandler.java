package com.google.ar.core.examples.java.filehandling;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.Objects;

/** Creates and locates files belonging to scan sessions in a user-selected document tree. */
public final class FileHandler {
  private final ContentResolver contentResolver;
  private final Uri treeUri;
  private final Uri rootDocumentUri;

  // This directory is shared by all sessions created by this FileHandler instance.
  private Uri scansUri;

  /**
   * Creates a handler for a tree previously returned by ACTION_OPEN_DOCUMENT_TREE.
   * The Activity remains responsible for persisting permission to {@code treeUri}.
   */
  public FileHandler(Context context, Uri treeUri) {
    Objects.requireNonNull(context, "context");
    this.treeUri = Objects.requireNonNull(treeUri, "treeUri");
    this.contentResolver = context.getApplicationContext().getContentResolver();
    this.rootDocumentUri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri));
  }

  /** Returns the selected document tree that grants access to all stored child URIs. */
  public Uri getTreeUri() {
    return treeUri;
  }

  /** Finds or creates the workspace's shared scans directory. */
  public synchronized Uri prepareWorkspace() throws IOException {
    if (scansUri == null) {
      scansUri = findOrCreateDirectory(contentResolver, treeUri, rootDocumentUri, "scans");
    }
    return scansUri;
  }

  /** Creates one new scan directory and returns its immutable collection of child URIs. */
  public ScanDirectories createScanDirectories(String scanId) throws IOException {
    requireValidName(scanId, "scanId");

    Uri scanUri = createDirectory(contentResolver, prepareWorkspace(), scanId);
    Uri imagesUri = createDirectory(contentResolver, scanUri, "images");
    Uri depthUri = createDirectory(contentResolver, scanUri, "depth");
    Uri confidenceUri = createDirectory(contentResolver, scanUri, "confidence");

    return new ScanDirectories(scanUri, imagesUri, depthUri, confidenceUri);
  }

  /** Creates a file under a document-provider directory. */
  public Uri createFile(Uri parentDirectoryUri, String mimeType, String fileName)
      throws IOException {
    Objects.requireNonNull(parentDirectoryUri, "parentDirectoryUri");
    requireValidName(mimeType, "mimeType");
    requireValidName(fileName, "fileName");

    Uri createdFile =
        DocumentsContract.createDocument(
            contentResolver, parentDirectoryUri, mimeType, fileName);

    if (createdFile == null) {
      throw new IOException("Could not create file: " + fileName);
    }

    return createdFile;
  }

  public static Uri createDirectory(
      ContentResolver resolver, Uri parentDirectoryUri, String directoryName)
      throws IOException {
    Objects.requireNonNull(resolver, "resolver");
    Objects.requireNonNull(parentDirectoryUri, "parentDirectoryUri");
    requireValidName(directoryName, "directoryName");

    Uri createdDirectory =
        DocumentsContract.createDocument(
            resolver,
            parentDirectoryUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            directoryName);

    if (createdDirectory == null) {
      throw new IOException("Could not create directory: " + directoryName);
    }

    return createdDirectory;
  }

  public static Uri findOrCreateDirectory(
      ContentResolver resolver,
      Uri treeUri,
      Uri parentDirectoryUri,
      String directoryName)
      throws IOException {
    Uri existingDirectory =
        findDirectory(resolver, treeUri, parentDirectoryUri, directoryName);

    return existingDirectory != null
        ? existingDirectory
        : createDirectory(resolver, parentDirectoryUri, directoryName);
  }

  private static Uri findDirectory(
      ContentResolver resolver,
      Uri treeUri,
      Uri parentDirectoryUri,
      String directoryName)
      throws IOException {
    Objects.requireNonNull(resolver, "resolver");
    Objects.requireNonNull(treeUri, "treeUri");
    Objects.requireNonNull(parentDirectoryUri, "parentDirectoryUri");
    requireValidName(directoryName, "directoryName");

    String parentDocumentId = DocumentsContract.getDocumentId(parentDirectoryUri);
    Uri childrenUri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);

    String[] projection = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    try (Cursor cursor = contentQuery(resolver, childrenUri, projection)) {
      int idColumn =
          cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
      int nameColumn =
          cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
      int mimeTypeColumn =
          cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);

      while (cursor.moveToNext()) {
        if (!directoryName.equals(cursor.getString(nameColumn))) {
          continue;
        }

        String mimeType = cursor.getString(mimeTypeColumn);
        if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
          throw new IOException("A file already exists with the name: " + directoryName);
        }

        return DocumentsContract.buildDocumentUriUsingTree(
            treeUri, cursor.getString(idColumn));
      }
    }

    return null;
  }

  private static Cursor contentQuery(
      ContentResolver resolver, Uri childrenUri, String[] projection) throws IOException {
    Cursor cursor = resolver.query(childrenUri, projection, null, null, null);
    if (cursor == null) {
      throw new IOException("Could not query directory: " + childrenUri);
    }
    return cursor;
  }

  private static void requireValidName(String value, String parameterName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(parameterName + " must not be empty");
    }
  }

  /** Immutable URIs for one scan; safe to pass to background file-writing tasks. */
  public static final class ScanDirectories {
    private final Uri scanUri;
    private final Uri imagesUri;
    private final Uri depthUri;
    private final Uri confidenceUri;

    private ScanDirectories(Uri scanUri, Uri imagesUri, Uri depthUri, Uri confidenceUri) {
      this.scanUri = scanUri;
      this.imagesUri = imagesUri;
      this.depthUri = depthUri;
      this.confidenceUri = confidenceUri;
    }

    public Uri getScanUri() {
      return scanUri;
    }

    public Uri getImagesUri() {
      return imagesUri;
    }

    public Uri getDepthUri() {
      return depthUri;
    }

    public Uri getConfidenceUri() {
      return confidenceUri;
    }
  }
}
