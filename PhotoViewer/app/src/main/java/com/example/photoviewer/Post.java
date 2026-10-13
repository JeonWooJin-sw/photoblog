package com.example.photoviewer;

import android.graphics.Bitmap;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** 서버 API(/api_root/Post/)의 게시물 1개 */
public class Post {

    public final int id;
    public final int author;
    public final String title;
    public final String text;
    public final String date;       // published_date (없으면 created_date)
    public final String imageUrl;
    public Bitmap image;            // 동기화할 때 내려받은 이미지 (실패하면 null)

    public Post(int id, int author, String title, String text, String date, String imageUrl) {
        this.id = id;
        this.author = author;
        this.title = title;
        this.text = text;
        this.date = date;
        this.imageUrl = imageUrl;
    }

    public static Post fromJson(JSONObject json) throws JSONException {
        String date = json.isNull("published_date")
                ? json.optString("created_date", "")
                : json.getString("published_date");
        return new Post(
                json.optInt("id", -1),
                json.optInt("author", -1),
                json.optString("title", ""),
                json.optString("text", ""),
                date,
                json.optString("image", ""));
    }

    /** "2026-10-13T15:59:20.312+09:00" -> "2026-10-13 15:59" */
    public String displayDate() {
        if (date == null) return "";
        if (date.length() < 16 || date.charAt(10) != 'T') return date;
        return date.substring(0, 10) + " " + date.substring(11, 16);
    }

    /** 검색어가 제목이나 내용에 들어 있는지 (대소문자 무시) */
    public boolean matches(String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        return title.toLowerCase(Locale.ROOT).contains(q) || text.toLowerCase(Locale.ROOT).contains(q);
    }
}
