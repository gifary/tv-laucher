package dev.gtv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Tiny HTTP helper: JSON as text, thumbnails as downsampled bitmaps. */
final class Net {

    /** Decoded thumbnails only, about 8 MB. */
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<>(8 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private Net() {
    }

    static String get(String spec) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        try {
            try (InputStream in = c.getInputStream();
                 BufferedReader r =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Already decoded thumbnail, or null. Safe from the main thread. */
    static Bitmap peek(String spec) {
        return IMAGES.get(spec);
    }

    /** Downloads an image and scales it to about targetW pixels wide. */
    static Bitmap image(String spec, int targetW) throws Exception {
        Bitmap cached = IMAGES.get(spec);
        if (cached != null) return cached;

        HttpURLConnection c = (HttpURLConnection) new URL(spec).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        try {
            byte[] bytes;
            try (InputStream in = c.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                bytes = out.toByteArray();
            }

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = sampleSize(o.outWidth, targetW);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (bitmap != null) IMAGES.put(spec, bitmap);
            return bitmap;
        } finally {
            c.disconnect();
        }
    }

    private static int sampleSize(int width, int target) {
        int size = 1;
        while (width / (size * 2) >= target) size *= 2;
        return size;
    }
}
