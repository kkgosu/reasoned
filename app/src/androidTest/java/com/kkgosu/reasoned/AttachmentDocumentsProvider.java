package com.kkgosu.reasoned;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Test APK process has no target-app Kotlin runtime; use framework/Java only. */
public class AttachmentDocumentsProvider extends ContentProvider {
    private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS};

    @Override public boolean onCreate() { return true; }
    private String type(String id) {
        if (id.equals("root") || id.equals("notes")) return Document.MIME_TYPE_DIR;
        return id.equals("skip.png") ? "image/png" : "text/plain";
    }
    private void row(MatrixCursor cursor, String id) {
        if (!Arrays.asList("root", "notes", "note.txt", "nested.md", "skip.png").contains(id)) throw new IllegalArgumentException();
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            if (column.equals(Document.COLUMN_DOCUMENT_ID) || column.equals(Document.COLUMN_DISPLAY_NAME)) row.add(id);
            else if (column.equals(Document.COLUMN_MIME_TYPE)) row.add(type(id));
            else if (column.equals(Document.COLUMN_FLAGS)) row.add(0);
            else row.add(null);
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        String id = DocumentsContract.getDocumentId(uri);
        if ("children".equals(uri.getLastPathSegment())) {
            if (id.equals("root")) for (String child : new String[]{"note.txt", "notes", "skip.png"}) row(cursor, child);
            else if (id.equals("notes")) row(cursor, "nested.md");
            else throw new IllegalArgumentException();
        } else row(cursor, id);
        return cursor;
    }
    @Override public String getType(Uri uri) { return type(DocumentsContract.getDocumentId(uri)); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = DocumentsContract.getDocumentId(uri);
        if (!mode.equals("r") || !(id.equals("note.txt") || id.equals("nested.md"))) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), id);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(("[Authored offline folder source] " + id).getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) { throw new FileNotFoundException(); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
