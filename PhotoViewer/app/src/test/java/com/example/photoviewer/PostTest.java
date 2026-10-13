package com.example.photoviewer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** 서버 JSON -> Post 변환, 날짜 표시, 검색 테스트 */
public class PostTest {

    private static final String JSON = "{\"id\":3,\"author\":1,\"title\":\"우주 이야기\","
            + "\"text\":\"제임스웹이 보내온 성운\","
            + "\"created_date\":\"2026-10-13T15:00:00+09:00\","
            + "\"published_date\":\"2026-10-13T15:59:20.312324+09:00\","
            + "\"image\":\"http://10.0.2.2:8000/media/images/2026/10/13/nebula.jpg\"}";

    @Test
    public void fromJson_readsAllFields() throws Exception {
        Post post = Post.fromJson(new JSONObject(JSON));

        assertEquals(3, post.id);
        assertEquals(1, post.author);
        assertEquals("우주 이야기", post.title);
        assertEquals("제임스웹이 보내온 성운", post.text);
        assertEquals("http://10.0.2.2:8000/media/images/2026/10/13/nebula.jpg", post.imageUrl);
        assertEquals("2026-10-13 15:59", post.displayDate());
    }

    @Test
    public void fromJson_usesCreatedDateWhenNotPublished() throws Exception {
        JSONObject json = new JSONObject(JSON);
        json.put("published_date", JSONObject.NULL);

        assertEquals("2026-10-13 15:00", Post.fromJson(json).displayDate());
    }

    @Test
    public void fromJson_missingIdBecomesMinusOne() throws Exception {
        Post post = Post.fromJson(new JSONObject("{\"title\":\"t\",\"text\":\"x\"}"));

        assertEquals(-1, post.id);
        assertEquals("", post.imageUrl);
    }

    @Test
    public void displayDate_keepsUnknownFormat() {
        assertEquals("어제", new Post(1, 1, "t", "x", "어제", "").displayDate());
        assertEquals("", new Post(1, 1, "t", "x", null, "").displayDate());
    }

    @Test
    public void matches_titleOrText_ignoringCase() {
        Post post = new Post(1, 1, "Photo Blog", "서진이가 그림을 그린다", "", "");

        assertTrue(post.matches(""));
        assertTrue(post.matches(null));
        assertTrue(post.matches("photo"));
        assertTrue(post.matches("  그림 "));
        assertFalse(post.matches("우주"));
    }
}
