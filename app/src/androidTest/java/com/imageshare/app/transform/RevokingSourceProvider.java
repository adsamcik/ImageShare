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
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
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
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.contains("huge")) {
            return ParcelFileDescriptor.open(hugePngFile(), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (!path.contains("stable") && OPENS.incrementAndGet() > 1) {
            throw new SecurityException("grant revoked after pre-flight");
        }
        return ParcelFileDescriptor.open(jpegFile(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File jpegFile() throws FileNotFoundException {
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
        return file;
    }

    private File hugePngFile() throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("missing context");
        }
        File file = new File(getContext().getCacheDir(), "huge-20000x20000-valid.png");
        if (!file.isFile()) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                writeHugePng(output, 20_000, 20_000);
            } catch (Exception error) {
                throw new FileNotFoundException(error.getMessage());
            }
        }
        return file;
    }

    private static void writeHugePng(FileOutputStream output, int width, int height) throws IOException {
        output.write(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(ihdr)) {
            data.writeInt(width);
            data.writeInt(height);
            data.writeByte(8);
            data.writeByte(0);
            data.writeByte(0);
            data.writeByte(0);
            data.writeByte(0);
        }
        writePngChunk(output, "IHDR", ihdr.toByteArray());
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            byte[] row = new byte[width + 1];
            for (int y = 0; y < height; y++) {
                deflater.write(row);
            }
        }
        writePngChunk(output, "IDAT", compressed.toByteArray());
        writePngChunk(output, "IEND", new byte[0]);
    }

    private static void writePngChunk(FileOutputStream output, String type, byte[] data) throws IOException {
        try (ByteArrayOutputStream chunkBytes = new ByteArrayOutputStream();
             DataOutputStream chunk = new DataOutputStream(chunkBytes)) {
            chunk.writeInt(data.length);
            byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            chunk.write(typeBytes);
            chunk.write(data);
            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            crc.update(data);
            chunk.writeInt((int) crc.getValue());
            output.write(chunkBytes.toByteArray());
        }
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
