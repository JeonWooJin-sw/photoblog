package com.example.photoviewer;

import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 포토블로그 서버 REST API 호출 (수업 자료처럼 HttpURLConnection 사용).
 * 네트워크 작업이라 반드시 백그라운드 스레드에서 호출해야 한다.
 */
public class PhotoBlogApi {

    public static final String POST_PATH = "/api_root/Post/";
    public static final String TOKEN_PATH = "/api-token-auth/";

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 15000;

    /** 서버가 2xx가 아닌 응답을 준 경우 (code = HTTP 상태 코드, message = 응답 본문) */
    public static class ApiException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int code;

        public ApiException(int code, String body) {
            super(body);
            this.code = code;
        }
    }

    private final String baseUrl;
    private final String token;

    public PhotoBlogApi(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    /** GET /api_root/Post/ : 게시물 목록 (JSON Array) */
    public List<Post> fetchPosts() throws IOException, JSONException {
        HttpURLConnection conn = open(baseUrl + POST_PATH, "GET");
        try {
            JSONArray array = new JSONArray(readResponse(conn));
            List<Post> posts = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                posts.add(Post.fromJson(array.getJSONObject(i)));
            }
            return posts;
        } finally {
            conn.disconnect();
        }
    }

    /** 이미지 URL에서 사진을 받아 maxWidth에 맞게 줄여서 디코딩 */
    public Bitmap downloadImage(String imageUrl, int maxWidth) throws IOException {
        if (imageUrl == null || imageUrl.isEmpty()) return null;
        HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream is = conn.getInputStream()) {
                return ImageUtils.decodeSampled(readBytes(is), maxWidth);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** POST /api_root/Post/ : 새 게시물 업로드 (multipart - title, text, image) */
    public Post uploadPost(String title, String text, byte[] jpeg, String fileName) throws IOException, JSONException {
        String boundary = "PhotoViewerBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipart(boundary, title, text, jpeg, fileName);

        HttpURLConnection conn = open(baseUrl + POST_PATH, "POST");
        try {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            // 길이를 정해서 보내야 Content-Length 헤더가 붙음 (소켓 서버도 이 값으로 body를 읽음)
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            Post post = Post.fromJson(new JSONObject(readResponse(conn)));
            if (post.id <= 0) throw new JSONException("응답에 게시물 id가 없음");
            return post;
        } finally {
            conn.disconnect();
        }
    }

    /** POST /api-token-auth/ : 아이디, 비밀번호로 토큰 발급 */
    public String login(String username, String password) throws IOException, JSONException {
        String form = "username=" + URLEncoder.encode(username, "UTF-8")
                + "&password=" + URLEncoder.encode(password, "UTF-8");
        byte[] body = form.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = open(baseUrl + TOKEN_PATH, "POST");
        try {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            return new JSONObject(readResponse(conn)).getString("token");
        } finally {
            conn.disconnect();
        }
    }

    /** DELETE /api_root/Post/{id}/ : 게시물 삭제 (작성자만 가능) */
    public void deletePost(int postId) throws IOException {
        HttpURLConnection conn = open(baseUrl + POST_PATH + postId + "/", "DELETE");
        try {
            readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            // 수업 자료와 같은 형식 (서버는 "Token", "JWT" 둘 다 받음)
            conn.setRequestProperty("Authorization", "Token " + token);
        }
        return conn;
    }

    /** 응답 본문을 문자열로 읽음. 2xx가 아니면 ApiException */
    private static String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = "";
        if (stream != null) {
            try (InputStream is = stream) {
                body = new String(readBytes(is), StandardCharsets.UTF_8);
            }
        }
        if (code < 200 || code >= 300) throw new ApiException(code, body);
        return body;
    }

    static byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /**
     * multipart/form-data 본문 만들기
     * --boundary / Content-Disposition / (빈 줄) / 값 ... --boundary--
     */
    static byte[] buildMultipart(String boundary, String title, String text,
                                 byte[] image, String fileName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, boundary, "title", title);
        writeField(out, boundary, "text", text);
        if (image != null) {
            writeText(out, "--" + boundary + "\r\n");
            writeText(out, "Content-Disposition: form-data; name=\"image\"; filename=\"" + fileName + "\"\r\n");
            writeText(out, "Content-Type: image/jpeg\r\n\r\n");
            out.write(image);
            writeText(out, "\r\n");
        }
        writeText(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String boundary,
                                   String name, String value) throws IOException {
        writeText(out, "--" + boundary + "\r\n");
        writeText(out, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeText(out, value);
        writeText(out, "\r\n");
    }

    private static void writeText(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }
}
