package su.dsr.f515.qnx_adas;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only sharing of completed calibration ZIPs through temporary URI grants. */
public final class CalibrationProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File resolve(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) || !(getContext().getPackageName() + ".calibration").equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1 || !CalibrationArchive.validName(uri.getLastPathSegment()))
            throw new FileNotFoundException("Invalid calibration URI");
        File file = new File(new File(getContext().getFilesDir(), "calibration"), uri.getLastPathSegment());
        if (!file.isFile()) throw new FileNotFoundException("Snapshot not found");
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only snapshot");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file;
        try { file = resolve(uri); } catch (FileNotFoundException e) { return null; }
        if (projection == null) projection = new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(projection); Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = file.length();
        }
        cursor.addRow(row); return cursor;
    }
    @Override public String getType(Uri uri) { return "application/zip"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
}
