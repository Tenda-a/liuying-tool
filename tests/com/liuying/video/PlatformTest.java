package com.liuying.video;

public final class PlatformTest {
  public static void main(String[] args) {
    expect(Platform.DOUYIN, "https://v.douyin.com/abc/");
    expect(Platform.DOUYIN, "https://www.iesdouyin.com/share/video/1");
    expect(Platform.KUAISHOU, "https://v.kuaishou.com/abc");
    expect(Platform.KUAISHOU, "https://txmov2.a.yximgs.com/upic/test.mp4");
    expect(Platform.XIAOHONGSHU, "https://xhslink.com/a/abc");
    expect(Platform.BILIBILI, "https://b23.tv/abc");
    expect(Platform.YOUTUBE, "https://redirector.googlevideo.com/videoplayback");
    expect(Platform.UNKNOWN, "https://douyin.com.evil.example/video/1");
    expect(Platform.UNKNOWN, "https://example.com/?next=https://douyin.com");
    System.out.println("PlatformTest OK");
  }

  private static void expect(Platform expected, String url) {
    Platform actual = Platform.detect(url);
    if (actual != expected) {
      throw new AssertionError(url + ": expected " + expected + ", got " + actual);
    }
  }
}
