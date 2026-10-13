package com.example.photoviewer;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 사진 크기 줄이기 / 업로드용 JPEG 만들기 */
public final class ImageUtils {

    /** 업로드할 사진의 긴 변 최대 크기 (휴대폰 원본 사진은 너무 커서 줄여서 보냄) */
    public static final int UPLOAD_MAX_SIZE = 1920;
    private static final int JPEG_QUALITY = 90;

    private ImageUtils() {
    }

    /** 업로드 준비된 사진 (미리보기 + 전송할 JPEG) */
    public static final class UploadImage {
        public final Bitmap preview;
        public final byte[] jpeg;

        UploadImage(Bitmap preview, byte[] jpeg) {
            this.preview = preview;
            this.jpeg = jpeg;
        }
    }

    /** 목록용: 화면 폭에 맞게 줄여서 디코딩 (큰 사진을 그대로 올리면 메모리 부족) */
    public static Bitmap decodeSampled(byte[] data, int reqWidth) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;   // 이미지가 아님

        options.inSampleSize = calculateInSampleSize(options.outWidth, reqWidth);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    /** width를 reqWidth 이상으로 유지하면서 2의 배수로 줄이는 비율 */
    static int calculateInSampleSize(int width, int reqWidth) {
        int sample = 1;
        if (reqWidth <= 0) return sample;
        while (width / (sample * 2) >= reqWidth) {
            sample *= 2;
        }
        return sample;
    }

    /** 갤러리에서 고른 사진을 긴 변 maxSize 이하로 줄이고 JPEG로 변환 */
    public static UploadImage prepareUpload(ContentResolver resolver, Uri uri, int maxSize) throws IOException {
        Bitmap bitmap;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28 이상은 ImageDecoder로 읽으면서 바로 목표 크기로 줄임
            ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
            bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                Size size = info.getSize();
                float scale = Math.min(1f, (float) maxSize / Math.max(size.getWidth(), size.getHeight()));
                decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * scale)),
                        Math.max(1, Math.round(size.getHeight() * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } else {
            bitmap = decodeLegacy(resolver, uri, maxSize);
        }
        if (bitmap == null) throw new IOException("이미지를 읽을 수 없음: " + uri);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        return new UploadImage(bitmap, out.toByteArray());
    }

    private static Bitmap decodeLegacy(ContentResolver resolver, Uri uri, int maxSize) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream is = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, options);
        }
        options.inSampleSize = calculateInSampleSize(Math.max(options.outWidth, options.outHeight), maxSize);
        options.inJustDecodeBounds = false;
        try (InputStream is = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(is, null, options);
        }
    }

    /** 업로드 파일 이름: photo_20261013_153012.jpg */
    public static String makeFileName() {
        return "photo_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
    }

    /** 512000 -> "500KB", 2097152 -> "2.0MB" */
    public static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, Math.round(bytes / 1024f)) + "KB";
        return String.format(Locale.US, "%.1fMB", bytes / (1024f * 1024f));
    }
}
