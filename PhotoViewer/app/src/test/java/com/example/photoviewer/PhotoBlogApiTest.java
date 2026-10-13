package com.example.photoviewer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 업로드 요청(multipart) 본문과 이미지 축소 비율 계산 테스트 */
public class PhotoBlogApiTest {

    @Test
    public void buildMultipart_hasTextFieldsAndImage() throws Exception {
        byte[] image = {(byte) 0xFF, (byte) 0xD8, 0x01, 0x02};
        byte[] body = PhotoBlogApi.buildMultipart("BOUNDARY", "제목", "내용", image, "photo.jpg");
        String text = new String(body, StandardCharsets.ISO_8859_1);
        String utf8 = new String(body, StandardCharsets.UTF_8);

        assertTrue(utf8.startsWith("--BOUNDARY\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n제목\r\n"));
        assertTrue(utf8.contains("--BOUNDARY\r\nContent-Disposition: form-data; name=\"text\"\r\n\r\n내용\r\n"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"image\"; filename=\"photo.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n"));
        assertTrue(text.endsWith("\r\n--BOUNDARY--\r\n"));

        // 이미지 바이트가 그대로 들어갔는지
        int start = text.indexOf("image/jpeg\r\n\r\n") + "image/jpeg\r\n\r\n".length();
        assertArrayEquals(image, Arrays.copyOfRange(body, start, start + image.length));
    }

    @Test
    public void buildMultipart_withoutImage() throws Exception {
        byte[] body = PhotoBlogApi.buildMultipart("B", "t", "x", null, null);
        String text = new String(body, StandardCharsets.UTF_8);

        assertEquals("--B\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nt\r\n"
                + "--B\r\nContent-Disposition: form-data; name=\"text\"\r\n\r\nx\r\n"
                + "--B--\r\n", text);
    }

    @Test
    public void readBytes_readsWholeStream() throws Exception {
        byte[] data = new byte[20000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;

        assertArrayEquals(data, PhotoBlogApi.readBytes(new ByteArrayInputStream(data)));
    }

    @Test
    public void calculateInSampleSize_keepsAtLeastRequestedWidth() {
        assertEquals(1, ImageUtils.calculateInSampleSize(1080, 1080));
        assertEquals(1, ImageUtils.calculateInSampleSize(2000, 1080));
        assertEquals(2, ImageUtils.calculateInSampleSize(2160, 1080));
        assertEquals(4, ImageUtils.calculateInSampleSize(4032, 1000));
        assertEquals(1, ImageUtils.calculateInSampleSize(4032, 0));
    }

    @Test
    public void formatSize() {
        assertEquals("1KB", ImageUtils.formatSize(100));
        assertEquals("500KB", ImageUtils.formatSize(512000));
        assertEquals("2.0MB", ImageUtils.formatSize(2097152));
    }
}
