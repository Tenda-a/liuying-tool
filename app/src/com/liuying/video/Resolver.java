package com.liuying.video;

import android.webkit.CookieManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Resolver {
  private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\\\"']+", Pattern.CASE_INSENSITIVE);
  private static final Pattern[] VIDEO_PATTERNS = {
      Pattern.compile("<meta[^>]+property=[\\\"']og:video(?::url)?[\\\"'][^>]+content=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE),
      Pattern.compile("<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+property=[\\\"']og:video(?::url)?[\\\"']", Pattern.CASE_INSENSITIVE),
      Pattern.compile("<video[^>]+src=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE),
      Pattern.compile("[\\\"'](?:playAddr|play_addr|masterUrl|video_url|baseUrl|base_url)[\\\"']\\s*:\\s*[\\\"'](https?:[^\\\"']+)", Pattern.CASE_INSENSITIVE),
      Pattern.compile("[\\\"']url_list[\\\"']\\s*:\\s*\\[\\s*[\\\"'](https?:[^\\\"']+)", Pattern.CASE_INSENSITIVE)
  };
  private static final Pattern[] COVER_PATTERNS = {
      Pattern.compile("<meta[^>]+property=[\\\"']og:image[\\\"'][^>]+content=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE),
      Pattern.compile("<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+property=[\\\"']og:image[\\\"']", Pattern.CASE_INSENSITIVE)
  };
  private static final Pattern[] TITLE_PATTERNS = {
      Pattern.compile("<meta[^>]+property=[\\\"']og:title[\\\"'][^>]+content=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE),
      Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
  };

  private Resolver() {}

  static List<String> extractSupportedUrls(String text) {
    Set<String> urls = new LinkedHashSet<>();
    Matcher matcher = URL_PATTERN.matcher(text == null ? "" : text);
    while (matcher.find()) {
      String value = trimPunctuation(matcher.group());
      if (Platform.isSupported(value)) urls.add(value);
    }
    return new ArrayList<>(urls);
  }

  static List<VideoItem> resolveAll(String sourceUrl) throws Exception {
    Platform platform = Platform.detect(sourceUrl);
    if (platform == Platform.DOUYIN) return DouyinResolver.resolve(sourceUrl);
    if (platform == Platform.YOUTUBE) return YoutubeResolver.resolve(sourceUrl);
    List<VideoItem> result = new ArrayList<>();
    result.add(resolve(sourceUrl));
    return result;
  }

  static VideoItem resolve(String sourceUrl) throws Exception {
    Platform platform = Platform.detect(sourceUrl);
    if (platform == Platform.UNKNOWN) throw new SecurityException("不支持的入口域名");
    HttpResult result = request(sourceUrl, platform);
    String title = decodeHtml(firstMatch(result.body, TITLE_PATTERNS));
    if (title == null || title.trim().isEmpty()) title = platform.displayName + " 视频";
    String media = normalize(firstMatch(result.body, VIDEO_PATTERNS), result.url);
    String cover = normalize(firstMatch(result.body, COVER_PATTERNS), result.url);
    if (platform == Platform.YOUTUBE && !isYouTubeDownloadUrl(media)) {
      throw new IllegalStateException("YouTube 页面暴露的是播放器/预览地址，不是完整视频流；移动端内置解析暂不下载，避免只保存几 KB 就显示完成");
    }
    String message = media != null ? "已解析直链"
        : platform == Platform.YOUTUBE ? "YouTube 高清流需要服务端 yt-dlp 解析"
        : "页面需要登录或平台签名，先登录后重试";
    if (media == null) {
      throw new IllegalStateException(message);
    }
    return new VideoItem(platform, sourceUrl, result.url, cleanTitle(title), media, cover, message);
  }

  private static HttpResult request(String rawUrl, Platform platform) throws Exception {
    String current = rawUrl;
    for (int redirects = 0; redirects < 6; redirects++) {
      URL url = new URL(current);
      validateUrl(url, platform);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(12000);
      connection.setReadTimeout(18000);
      connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
      connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7");
      String cookies = CookieManager.getInstance().getCookie(current);
      if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
      int status = connection.getResponseCode();
      if (status >= 300 && status < 400) {
        String location = connection.getHeaderField("Location");
        connection.disconnect();
        if (location == null) throw new IllegalStateException("重定向缺少 Location");
        current = new URL(url, location).toString();
        validateUrl(new URL(current), platform);
        continue;
      }
      InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String body = readLimited(stream, 4 * 1024 * 1024);
      String finalUrl = connection.getURL().toString();
      connection.disconnect();
      validateUrl(new URL(finalUrl), platform);
      if (status >= 400) throw new IllegalStateException("HTTP " + status);
      return new HttpResult(finalUrl, body);
    }
    throw new IllegalStateException("重定向次数过多");
  }

  private static void validateUrl(URL url, Platform platform) {
    String scheme = url.getProtocol();
    if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
      throw new SecurityException("不支持的协议");
    }
    if (!platform.allowsUrl(url.toString())) {
      throw new SecurityException("不支持的目标域名: " + url.getHost());
    }
  }

  private static String readLimited(InputStream stream, int maxChars) throws Exception {
    if (stream == null) return "";
    StringBuilder builder = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      char[] chars = new char[4096];
      int read;
      while ((read = reader.read(chars)) >= 0 && builder.length() < maxChars) {
        builder.append(chars, 0, Math.min(read, maxChars - builder.length()));
      }
    }
    return builder.toString();
  }

  private static String firstMatch(String text, Pattern[] patterns) {
    for (Pattern pattern : patterns) {
      Matcher matcher = pattern.matcher(text == null ? "" : text);
      if (matcher.find()) return unescape(matcher.group(1));
    }
    return null;
  }

  private static String normalize(String value, String baseUrl) {
    if (value == null || value.isEmpty()) return null;
    try {
      if (value.startsWith("//")) return "https:" + value;
      return new URI(baseUrl).resolve(value).toString();
    } catch (Exception ignored) {
      return value;
    }
  }

  private static boolean isYouTubeDownloadUrl(String value) {
    if (value == null || value.isEmpty()) return false;
    try {
      URI uri = new URI(value);
      String host = uri.getHost();
      String path = uri.getPath();
      if (host == null || path == null) return false;
      host = host.toLowerCase(java.util.Locale.ROOT);
      return host.endsWith("googlevideo.com") && path.contains("/videoplayback");
    } catch (Exception ignored) {
      return false;
    }
  }

  private static String unescape(String value) {
    return value.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&");
  }

  private static String decodeHtml(String value) {
    if (value == null) return null;
    return value.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
  }

  private static String cleanTitle(String value) {
    String clean = value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim();
    return clean.length() > 100 ? clean.substring(0, 100) : clean;
  }

  private static String trimPunctuation(String value) {
    while (!value.isEmpty() && ".,;:!?)]}。，；：！？）】》".indexOf(value.charAt(value.length() - 1)) >= 0) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }

  private static final class HttpResult {
    final String url;
    final String body;
    HttpResult(String url, String body) { this.url = url; this.body = body; }
  }
}
