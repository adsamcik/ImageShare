package com.imageshare.app.transform;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

public class RevokingSourceProvider extends ContentProvider {
    private static final AtomicInteger OPENS = new AtomicInteger(0);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read-only");
        }
        if (OPENS.incrementAndGet() > 1) {
            throw new SecurityException("grant revoked after pre-flight");
        }
        if (getContext() == null) {
            throw new FileNotFoundException("missing context");
        }
        File file = new File(getContext().getCacheDir(), "revoking-source.jpg");
        if (!file.isFile()) {
            Bitmap bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888);
            try {
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    for (int x = 0; x < bitmap.getWidth(); x++) {
                        bitmap.setPixel(x, y, Color.rgb(64, x * 255 / bitmap.getWidth(), y * 255 / bitmap.getHeight()));
                    }
                }
                try (FileOutputStream output = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
                } catch (Exception error) {
                    throw new FileNotFoundException(error.getMessage());
                }
            } finally {
                bitmap.recycle();
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    public static void reset() {
        OPENS.set(0);
    }
}
