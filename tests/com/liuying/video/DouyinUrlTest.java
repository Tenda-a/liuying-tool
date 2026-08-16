package com.liuying.video;

public final class DouyinUrlTest {
  public static void main(String[] args) {
    assertEquals(
        "https://aweme.snssdk.com/aweme/v1/play/?video_id=abc",
        DouyinBrowserActivity.normalizeVideoUrl(
            "http://aweme.snssdk.com/aweme/v1/playwm/?video_id=abc"));
    assertEquals("https://example.com/video.mp4",
        DouyinBrowserActivity.normalizeVideoUrl("https://example.com/video.mp4"));
  }

  private static void assertEquals(String expected, String actual) {
    if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
  }
}
