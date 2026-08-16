package com.liuying.video;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class YoutubeResolver {
  private static final String WEB_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
  private static final int MAX_RESPONSE_CHARS = 4 * 1024 * 1024;
  private static final String NETWORK_UNAVAILABLE_MESSAGE =
      "当前网络无法访问 YouTube/Google,请先配置可用网络后重试";
  private static final Pattern VIDEO_ID = Pattern.compile(
      "(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/|/v/)([0-9A-Za-z_-]{11})(?:[^0-9A-Za-z_-]|$)",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern CHANNEL_URL = Pattern.compile(
      "https?://(?:www\\.|m\\.)?youtube\\.com/(?:@[^/?#]+|channel/[^/?#]+|c/[^/?#]+|user/[^/?#]+)(?:/(?:videos|shorts|streams))?/?(?:[?#].*)?",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern PAGE_VIDEO_ID =
      Pattern.compile("\\\"videoId\\\"\\s*:\\s*\\\"([0-9A-Za-z_-]{11})\\\"");
  private static final Pattern CIPHER_SIGNATURE = Pattern.compile("(?:^|&)s=[^&]+");



  private YoutubeResolver() {}

  static List<VideoItem> resolve(String sourceUrl) throws Exception {
    if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
      throw new IllegalStateException("YouTube 地址为空");
    }
    String videoId = extractVideoId(sourceUrl);
    if (videoId != null) {
      List<VideoItem> result = new ArrayList<>();
      result.add(resolveVideo(sourceUrl, videoId));
      return result;
    }
    if (CHANNEL_URL.matcher(sourceUrl.trim()).matches()) return resolveChannel(sourceUrl.trim());
    throw new IllegalStateException("无法识别 YouTube 视频或频道地址");
  }

  private static List<VideoItem> resolveChannel(String sourceUrl) throws Exception {
    String pageUrl = channelVideosUrl(sourceUrl);
    String html;
    try {
      html = executeGet(pageUrl, MAX_RESPONSE_CHARS);
    } catch (Exception error) {
      if (isNetworkUnavailable(error)) throw networkUnavailable(error);
      throw error;
    }
    Set<String> ids = new LinkedHashSet<>();
    Matcher matcher = PAGE_VIDEO_ID.matcher(html);
    while (matcher.find() && ids.size() < 12) ids.add(matcher.group(1));
    if (ids.isEmpty()) throw new IllegalStateException("YouTube 频道页面未找到视频");

    List<VideoItem> result = new ArrayList<>();
    Exception lastError = null;
    for (String id : ids) {
      try {
        result.add(resolveVideo(sourceUrl, id));
      } catch (Exception error) {
        if (isNetworkUnavailable(error)) throw networkUnavailable(error);
        lastError = error;
      }
    }
    if (!result.isEmpty()) return result;
    String detail = lastError == null ? "未知错误" : errorMessage(lastError);
    throw new IllegalStateException("YouTube 频道视频全部解析失败,最后错误: " + detail, lastError);
  }

  private static VideoItem resolveVideo(String sourceUrl, String videoId) throws Exception {
    try {
      return resolveViaWebPage(sourceUrl, videoId);
    } catch (Exception error) {
      if (isNetworkUnavailable(error)) throw networkUnavailable(error);
      String detail = errorMessage(error);
      if (detail.contains("LOGIN_REQUIRED")
          && (detail.contains("not a bot") || detail.contains("聊天机器人"))) {
        throw new IllegalStateException(
            "YouTube 要求验证真人。当前解析器缺少 yt-dlp JS challenge 能力,"
                + "系统浏览器登录不会同步 Cookie;请导入有效 YouTube Cookie 后重试",
            error);
      }
      throw new IllegalStateException(
          "YouTube 视频 " + videoId + " 解析失败: WEB: " + detail, error);
    }
  }

  private static VideoItem resolveViaWebPage(String sourceUrl, String videoId) throws Exception {
    String pageUrl = "https://www.youtube.com/watch?v=" + videoId;
    String html = executeGet(pageUrl, MAX_RESPONSE_CHARS);
    String playerJson = extractBalancedObject(html, "ytInitialPlayerResponse");
    if (playerJson == null) {
      throw new IllegalStateException("网页未包含 ytInitialPlayerResponse");
    }
    return parsePlayerResponse(new JSONObject(playerJson), sourceUrl, videoId);
  }

  private static VideoItem parsePlayerResponse(
      JSONObject root, String sourceUrl, String videoId) throws Exception {
    JSONObject playability = root.optJSONObject("playabilityStatus");
    if (playability != null) {
      String status = playability.optString("status", "");
      if (!"OK".equals(status)) {
        String reason = playability.optString("reason", "视频不可用");
        throw new IllegalStateException("YouTube " + status + ": " + reason);
      }
    }
    JSONObject videoDetails = root.optJSONObject("videoDetails");
    String title = videoDetails == null ? "youtube_" + videoId
        : videoDetails.optString("title", "youtube_" + videoId);
    String author = videoDetails == null ? null : videoDetails.optString("author", null);
    long durationSeconds = videoDetails == null ? 0
        : videoDetails.optLong("lengthSeconds", 0);
    String coverUrl = extractCoverUrl(videoDetails, videoId);

    JSONObject streamingData = root.optJSONObject("streamingData");
    if (streamingData == null) throw new IllegalStateException("YouTube 未返回流数据");
    String mediaUrl = pickBestFormat(streamingData);
    return new VideoItem(Platform.YOUTUBE, sourceUrl,
        "https://www.youtube.com/watch?v=" + videoId, title, mediaUrl, coverUrl,
        "已获取视频直链", author, 0, durationSeconds, "video", null, videoId, null);
  }

  private static String extractCoverUrl(JSONObject videoDetails, String videoId) {
    if (videoDetails != null) {
      JSONObject thumbnail = videoDetails.optJSONObject("thumbnail");
      JSONArray thumbnails = thumbnail == null ? null : thumbnail.optJSONArray("thumbnails");
      if (thumbnails != null && thumbnails.length() > 0) {
        JSONObject best = thumbnails.optJSONObject(thumbnails.length() - 1);
        if (best != null) return best.optString("url", null);
      }
    }
    return "https://i.ytimg.com/vi/" + videoId + "/maxresdefault.jpg";
  }

  private static String pickBestFormat(JSONObject streamingData) throws Exception {
    JSONArray formats = streamingData.optJSONArray("formats");
    String direct = pickBestMp4(formats, true);
    if (direct != null) return direct;

    JSONArray adaptive = streamingData.optJSONArray("adaptiveFormats");
    direct = pickBestMp4(adaptive, false);
    if (direct != null) return direct;

    if (containsSignature(formats) || containsSignature(adaptive)) {
      throw new IllegalStateException("YouTube 格式包含 s 签名,需要播放器签名解密");
    }
    throw new IllegalStateException("YouTube 未找到可用的 MP4 直链");
  }

  private static String pickBestMp4(JSONArray formats, boolean requireAudio) throws Exception {
    if (formats == null) return null;
    String best = null;
    int bestHeight = -1;
    for (int i = 0; i < formats.length(); i++) {
      JSONObject format = formats.optJSONObject(i);
      if (format == null) continue;
      String mime = format.optString("mimeType", "").toLowerCase(java.util.Locale.ROOT);
      boolean hasAudio = format.optInt("audioChannels", 0) > 0
          || !format.optString("audioQuality", "").isEmpty();
      if (!mime.startsWith("video/mp4") || (requireAudio && !hasAudio)) continue;
      String url = extractFormatUrl(format);
      if (url == null) continue;
      int height = format.optInt("height", 0);
      if (best == null || height > bestHeight) {
        best = url;
        bestHeight = height;
      }
    }
    return best;
  }

  private static String extractFormatUrl(JSONObject format) throws Exception {
    String direct = format.optString("url", "");
    if (isHttpUrl(direct)) return direct;
    String cipher = format.optString("signatureCipher", "");
    if (cipher.isEmpty()) cipher = format.optString("cipher", "");
    if (cipher.isEmpty()) return null;
    String encodedUrl = null;
    String[] fields = cipher.split("&");
    for (String field : fields) {
      int equals = field.indexOf('=');
      String key = equals < 0 ? field : field.substring(0, equals);
      String value = equals < 0 ? "" : field.substring(equals + 1);
      if ("s".equals(key) && !value.isEmpty()) return null;
      if ("url".equals(key)) encodedUrl = value;
    }
    if (encodedUrl == null) return null;
    String decoded = URLDecoder.decode(encodedUrl, "UTF-8");
    return isHttpUrl(decoded) ? decoded : null;
  }

  private static boolean containsSignature(JSONArray formats) {
    if (formats == null) return false;
    for (int i = 0; i < formats.length(); i++) {
      JSONObject format = formats.optJSONObject(i);
      if (format == null) continue;
      String cipher = format.optString("signatureCipher", "");
      if (cipher.isEmpty()) cipher = format.optString("cipher", "");
      if (CIPHER_SIGNATURE.matcher(cipher).find()) return true;
    }
    return false;
  }

  private static String extractBalancedObject(String text, String marker) {
    int markerAt = text.indexOf(marker);
    while (markerAt >= 0) {
      int start = text.indexOf('{', markerAt + marker.length());
      if (start < 0) return null;
      boolean inString = false;
      boolean escaped = false;
      int depth = 0;
      for (int i = start; i < text.length(); i++) {
        char c = text.charAt(i);
        if (inString) {
          if (escaped) escaped = false;
          else if (c == '\\') escaped = true;
          else if (c == '"') inString = false;
          continue;
        }
        if (c == '"') inString = true;
        else if (c == '{') depth++;
        else if (c == '}' && --depth == 0) return text.substring(start, i + 1);
      }
      markerAt = text.indexOf(marker, markerAt + marker.length());
    }
    return null;
  }

  private static String executeGet(String url, int maximum) throws Exception {
    HttpURLConnection connection = null;
    try {
      connection = openConnection(url);
      connection.setRequestMethod("GET");
      connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
      int status = connection.getResponseCode();
      if (status >= 400) throw httpError(connection, status);
      return readStream(connection.getInputStream(), maximum);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }



  private static HttpURLConnection openConnection(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(20000);
    connection.setRequestProperty("User-Agent", WEB_USER_AGENT);
    String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
    if (cookies != null && !cookies.trim().isEmpty()) {
      connection.setRequestProperty("Cookie", cookies);
    }
    return connection;
  }

  private static IllegalStateException httpError(HttpURLConnection connection, int status) {
    String detail = "";
    try {
      detail = readStream(connection.getErrorStream(), 16 * 1024).trim();
    } catch (Exception error) {
      detail = errorMessage(error);
    }
    if (detail.length() > 300) detail = detail.substring(0, 300);
    return new IllegalStateException("HTTP " + status + (detail.isEmpty() ? "" : ": " + detail));
  }

  private static String channelVideosUrl(String sourceUrl) {
    String clean = sourceUrl;
    int query = clean.indexOf('?');
    if (query >= 0) clean = clean.substring(0, query);
    int fragment = clean.indexOf('#');
    if (fragment >= 0) clean = clean.substring(0, fragment);
    while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
    String lower = clean.toLowerCase(java.util.Locale.ROOT);
    if (!lower.endsWith("/videos") && !lower.endsWith("/shorts")
        && !lower.endsWith("/streams")) {
      clean += "/videos";
    }
    return clean;
  }

  private static String extractVideoId(String url) {
    Matcher matcher = VIDEO_ID.matcher(url);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static boolean isHttpUrl(String url) {
    return url != null && (url.startsWith("https://") || url.startsWith("http://"));
  }

  private static IllegalStateException networkUnavailable(Throwable cause) {
    return new IllegalStateException(NETWORK_UNAVAILABLE_MESSAGE, cause);
  }

  private static boolean isNetworkUnavailable(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof UnknownHostException
          || current instanceof NoRouteToHostException
          || current instanceof SocketTimeoutException
          || current instanceof ConnectException) {
        return true;
      }
      if (current instanceof SocketException) {
        String message = current.getMessage();
        if (message != null) {
          String normalized = message.toLowerCase(java.util.Locale.ROOT);
          if (normalized.contains("network is unreachable")
              || normalized.contains("no route to host")
              || normalized.contains("enetunreach")
              || normalized.contains("ehostunreach")) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static String errorMessage(Throwable error) {
    String message = error.getMessage();
    return message == null || message.trim().isEmpty()
        ? error.getClass().getSimpleName() : message.trim();
  }

  private static String join(List<String> values) {
    StringBuilder result = new StringBuilder();
    for (String value : values) {
      if (result.length() > 0) result.append("; ");
      result.append(value);
    }
    return result.toString();
  }

  private static String readStream(InputStream stream, int maxChars) throws Exception {
    if (stream == null) return "";
    StringBuilder builder = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      char[] buffer = new char[4096];
      int count;
      while ((count = reader.read(buffer)) >= 0 && builder.length() < maxChars) {
        builder.append(buffer, 0, Math.min(count, maxChars - builder.length()));
      }
    }
    return builder.toString();
  }


}
