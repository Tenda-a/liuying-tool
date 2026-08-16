package com.liuying.video;

import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DouyinResolver {
  private static final String USER_AGENT =
      "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
  private static final Pattern[] VIDEO_ID_PATTERNS = {
      Pattern.compile("/(?:video|note)/(\\d+)"),
      Pattern.compile("[?&](?:modal_id|aweme_id|item_id|vid)=(\\d+)"),
      Pattern.compile("/share/video/(\\d+)")
  };
  private static final Pattern USER_ID_PATTERN = Pattern.compile("/user/([^/?]+)");

  private DouyinResolver() {}

  static List<VideoItem> resolve(String sourceUrl) throws Exception {
    String resolvedUrl = followRedirects(sourceUrl);
    String videoId = extractVideoId(resolvedUrl);
    if (videoId != null) return singleton(resolveVideo(sourceUrl, resolvedUrl, videoId));

    String secUserId = extractUserId(resolvedUrl);
    if (secUserId != null) return resolveUserPage(sourceUrl, resolvedUrl, secUserId);
    throw new IllegalStateException("无法识别抖音视频 ID 或主页用户 ID");
  }

  private static VideoItem resolveVideo(String sourceUrl, String resolvedUrl, String videoId) throws Exception {
    Exception lastError = null;

    try {
      String detailApi = "https://www.douyin.com/aweme/v1/web/aweme/detail/"
          + "?device_platform=webapp&aid=6383&channel=channel_pc_web&aweme_id="
          + URLEncoder.encode(videoId, "UTF-8");
      JSONObject data = requestJson(detailApi, resolvedUrl);
      JSONObject aweme = firstObject(data, "aweme_detail", "awemeDetail", "item", "aweme");
      if (aweme == null) {
        JSONArray items = firstArray(data, "aweme_list", "awemeList", "item_list", "itemList");
        aweme = findAwemeById(items, videoId);
      }
      if (isTargetAweme(aweme, videoId)) return parseAweme(aweme, sourceUrl, resolvedUrl);
    } catch (Exception error) {
      lastError = error;
    }

    try {
      String pageUrl = "https://www.douyin.com/video/" + videoId;
      JSONObject embedded = extractAnyJson(requestText(pageUrl, resolvedUrl));
      JSONObject aweme = findAwemeObject(embedded, videoId);
      if (isTargetAweme(aweme, videoId)) return parseAweme(aweme, sourceUrl, pageUrl);
    } catch (Exception error) {
      lastError = error;
    }

    try {
      String api = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids="
          + URLEncoder.encode(videoId, "UTF-8");
      JSONObject data = requestJson(api, resolvedUrl);
      JSONArray items = firstArray(data, "item_list", "aweme_list");
      JSONObject aweme = findAwemeById(items, videoId);
      if (isTargetAweme(aweme, videoId)) return parseAweme(aweme, sourceUrl, resolvedUrl);
    } catch (Exception error) {
      lastError = error;
    }

    try {
      String shareUrl = "https://www.iesdouyin.com/share/video/" + videoId + "/";
      String html = requestText(shareUrl, resolvedUrl);
      JSONObject embedded = extractAnyJson(html);
      JSONObject aweme = findAwemeObject(embedded, videoId);
      if (isTargetAweme(aweme, videoId)) return parseAweme(aweme, sourceUrl, shareUrl);
      String media = extractPlayUrlFromHtml(html);
      if (media != null) {
        return new VideoItem(Platform.DOUYIN, sourceUrl, shareUrl, "douyin_" + videoId,
            normalizeMediaUrl(media), null, "分享页正则获取直链");
      }
    } catch (Exception error) {
      lastError = error;
    }

    throw new IllegalStateException("抖音单视频解析失败，可能需要重新登录或触发风控"
        + (lastError == null || lastError.getMessage() == null ? "" : "：" + lastError.getMessage()));
  }

  private static List<VideoItem> resolveUserPage(
      String sourceUrl, String resolvedUrl, String secUserId) throws Exception {
    // 路径 1:拉取用户主页 HTML,优先从 RENDER_DATA 提取公开作品。
    // 抖音登录态下 RENDER_DATA 包含 aweme_post 列表;未登录时也可能有少量公开作品。
    String htmlError = null;
    try {
      List<VideoItem> fromHtml = resolveUserPageFromHtml(resolvedUrl, secUserId);
      if (fromHtml != null && !fromHtml.isEmpty()) return fromHtml;
    } catch (Exception error) {
      htmlError = error.getMessage();
      // 继续尝试 API 路径;此处不报错。
    }
    // 路径 2:抖音 /aweme/post 接口,需要 X-Bogus/msToken 签名,登录后成功率较高。
    String cookies = cookiesFor("https://www.douyin.com/");
    if (cookies == null || cookies.trim().isEmpty()) {
      throw new IllegalStateException("未检测到抖音登录 Cookie,请先在设置页导入 Cookie 文件或完成登录");
    }
    String api = "https://www.douyin.com/aweme/v1/web/aweme/post/"
        + "?device_platform=webapp&aid=6383&channel=channel_pc_web&count=20&max_cursor=0"
        + "&sec_user_id=" + URLEncoder.encode(secUserId, "UTF-8");
    JSONObject data = requestJson(api, resolvedUrl);
    int statusCode = data.optInt("status_code", 0);
    if (statusCode != 0) {
      throw new IllegalStateException("抖音主页接口被风控(状态码 "
          + statusCode + "),请在登录页完成滑块验证后重试");
    }
    JSONArray awemes = firstArray(data, "aweme_list", "awemeList");
    if (awemes == null || awemes.length() == 0) {
      throw new IllegalStateException("主页未返回作品:网页解析失败"
          + (htmlError == null ? "" : "(" + htmlError + ")")
          + ",请确认已导入有效 Cookie 且账号未过期");
    }
    return collectAwemes(awemes, resolvedUrl);
  }

  /**
   * 从抖音用户主页 HTML 解析作品列表。
   * 抖音会在 /user/<sec_uid> 页面注入 RENDER_DATA,登录态下包含 aweme_post 列表,
   * 未登录态下也可能包含少量公开作品,这是不依赖签名的主要回退路径。
   */
  private static List<VideoItem> resolveUserPageFromHtml(String resolvedUrl, String secUserId) throws Exception {
    // 多个 URL 变体: 主页 / 带 showTab / iesdouyin 分享页。
    // 抖音对无 cookie 的主页请求可能返回空 body,逐一尝试直到拿到可用 HTML。
    String[] candidates = {
        "https://www.douyin.com/user/" + secUserId,
        "https://www.douyin.com/user/" + secUserId + "?showTab=post",
        "https://www.iesdouyin.com/share/user/" + secUserId,
        "https://www.douyin.com/user/" + secUserId + "?showTab=post&is_from_mobile=1"
    };
    Exception lastError = null;
    for (String userPageUrl : candidates) {
      String html = null;
      try {
        html = requestText(userPageUrl, resolvedUrl);
      } catch (Exception error) {
        lastError = error;
        continue;
      }
      if (html == null || html.isEmpty()) continue;
      // 优先 RENDER_DATA(JSON 形式)
      JSONObject render = extractRenderData(html);
      if (render != null) {
        JSONArray awemes = findAwemeArrayInRender(render);
        if (awemes != null && awemes.length() > 0) {
          List<VideoItem> collected = collectAwemes(awemes, userPageUrl);
          if (!collected.isEmpty()) return collected;
        }
      }
      // 遍历页面所有 script,依次尝试 RENDER_DATA / __INITIAL_STATE__ /
      // SIGI_STATE / __NEXT_DATA__ / _ROUTER_DATA / _SSR_DATA 等内嵌大 JSON。
      Matcher scriptMatcher = Pattern.compile(
          "<script[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
          .matcher(html);
      while (scriptMatcher.find()) {
        String text = scriptMatcher.group(1);
        if (text == null || text.length() < 128) continue;
        JSONObject parsed = null;
        if (text.contains("RENDER_DATA")) {
          parsed = parseJsonCandidate(text.replaceFirst(
              "^[^=]*=\\s*", "").replaceAll(";\\s*$", ""));
        }
        if (parsed == null && (text.contains("__INITIAL_STATE__")
            || text.contains("SIGI_STATE") || text.contains("__NEXT_DATA__")
            || text.contains("_ROUTER_DATA") || text.contains("_SSR_DATA"))) {
          Matcher assign = Pattern.compile(
              "=\\s*(\\{[\\s\\S]*\\})\\s*;?\\s*$").matcher(text.trim());
          if (assign.find()) parsed = parseJsonCandidate(assign.group(1));
        }
        if (parsed == null) {
          parsed = parseJsonCandidate(text);
        }
        if (parsed == null) {
          try {
            parsed = parseJsonCandidate(java.net.URLDecoder.decode(text, "UTF-8"));
          } catch (Exception ignored) {}
        }
        if (parsed != null) {
          JSONArray awemes = findAwemeArrayInRender(parsed);
          if (awemes != null && awemes.length() > 0) {
            List<VideoItem> collected = collectAwemes(awemes, userPageUrl);
            if (!collected.isEmpty()) return collected;
          }
        }
      }
      // 兜底: 直接从 HTML 提取 /video/<数字> 链接,不依赖 JSON 结构。
      List<VideoItem> byLinks = extractVideoLinksFromHtml(html, userPageUrl);
      if (byLinks != null && !byLinks.isEmpty()) return byLinks;
    }
    if (lastError != null) throw lastError;
    return null;
  }

  /**
   * 兜底: 从主页 HTML 里直接提取 /video/<数字> 链接生成待下载列表。
   * 抖音主页 HTML 即使没有完整 RENDER_DATA,也常包含大量视频链接。
   */
  private static List<VideoItem> extractVideoLinksFromHtml(String html, String userPageUrl) {
    if (html == null || html.isEmpty()) return null;
    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
    Matcher matcher = Pattern.compile(
        "(?:/video/|/note/)(\\d{8,})", Pattern.CASE_INSENSITIVE).matcher(html);
    while (matcher.find() && ids.size() < 20) {
      ids.add(matcher.group(1));
    }
    if (ids.isEmpty()) return null;
    List<VideoItem> result = new ArrayList<>();
    for (String id : ids) {
      String pageUrl = "https://www.douyin.com/video/" + id;
      try {
        VideoItem item = new VideoItem(Platform.DOUYIN, pageUrl, pageUrl,
            "douyin_" + id, null, null, "主页链接提取,等待解析详情", null,
            0, 0, "video", null, id, null);
        result.add(item);
      } catch (Exception ignored) {
      }
    }
    return result.isEmpty() ? null : result;
  }

  /**
   * 在 RENDER_DATA 大对象里递归找形如 [{aweme_id/video/aweme_detail...}] 的作品数组。
   * 抖音把作品数据放在 aweme_post / aweme_list / itemList 等多个嵌套键下面,
   * 必须深度遍历,只看顶层不够。
   */
  private static JSONArray findAwemeArrayInRender(Object value) {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      String[] arrayKeys = {"aweme_post", "aweme_list", "awemeList",
          "awemePost", "awemePostList", "post_list", "postList",
          "itemList", "item_list", "items", "video_list", "videoList"};
      for (String key : arrayKeys) {
        JSONArray array = object.optJSONArray(key);
        if (isLikelyAwemeArray(array)) return array;
      }
      JSONArray names = object.names();
      if (names != null) {
        for (int i = 0; i < names.length(); i++) {
          String key = names.optString(i);
          Object child = object.opt(key);
          if (key.toLowerCase(java.util.Locale.ROOT).contains("aweme")) {
            if (child instanceof JSONArray) {
              if (isLikelyAwemeArray((JSONArray) child)) return (JSONArray) child;
            } else if (child instanceof JSONObject) {
              JSONArray inner = ((JSONObject) child).optJSONArray("aweme_list");
              if (isLikelyAwemeArray(inner)) return inner;
            }
          }
          JSONArray found = findAwemeArrayInRender(child);
          if (found != null) return found;
        }
      }
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      if (isLikelyAwemeArray(array)) return array;
      for (int i = 0; i < array.length(); i++) {
        JSONArray found = findAwemeArrayInRender(array.opt(i));
        if (found != null) return found;
      }
    }
    return null;
  }

  /**
   * 校验一个 JSONArray 是否真的像作品列表,避免把页面里的推荐位/标签数组误判。
   * 至少需要其中一项含 aweme_id 或 video/images 字段。
   */
  private static boolean isLikelyAwemeArray(JSONArray array) {
    if (array == null || array.length() == 0) return false;
    int validCount = 0;
    for (int i = 0; i < Math.min(array.length(), 5); i++) {
      JSONObject item = array.optJSONObject(i);
      if (item == null) continue;
      boolean hasId = firstString(item, "aweme_id", "awemeId", "id", "group_id",
          "groupId", "item_id", "itemId") != null;
      boolean hasMedia = item.has("video") || item.has("images")
          || item.has("images_list") || item.has("imagesList");
      if (hasId || hasMedia) validCount++;
    }
    return validCount > 0;
  }

  private static List<VideoItem> collectAwemes(JSONArray awemes, String resolvedUrl) {
    List<VideoItem> result = new ArrayList<>();
    for (int i = 0; i < awemes.length(); i++) {
      JSONObject aweme = awemes.optJSONObject(i);
      if (aweme == null) continue;
      try {
        String id = firstString(aweme, "aweme_id", "awemeId", "id", "group_id");
        String pageUrl = id == null ? resolvedUrl : "https://www.douyin.com/video/" + id;
        result.add(parseAweme(aweme, pageUrl, pageUrl));
      } catch (Exception ignored) {
      }
    }
    return result;
  }

  private static JSONObject firstObject(JSONObject object, String... keys) {
    if (object == null) return null;
    for (String key : keys) {
      JSONObject value = object.optJSONObject(key);
      if (value != null) return value;
    }
    return null;
  }

  private static boolean isTargetAweme(JSONObject aweme, String videoId) {
    if (aweme == null || videoId == null) return false;
    String id = firstString(aweme, "aweme_id", "awemeId", "id", "group_id", "groupId", "item_id", "itemId");
    return videoId.equals(id);
  }

  private static JSONObject findAwemeById(JSONArray items, String videoId) {
    if (items == null) return null;
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (isTargetAweme(item, videoId)) return item;
    }
    return null;
  }

  private static JSONObject findAwemeObject(Object value, String videoId) {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      if (isTargetAweme(object, videoId)
          && (object.optJSONObject("video") != null
              || firstArray(object, "images", "images_list", "imagesList") != null)) {
        return object;
      }
      JSONArray names = object.names();
      if (names != null) for (int i = 0; i < names.length(); i++) {
        JSONObject found = findAwemeObject(object.opt(names.optString(i)), videoId);
        if (found != null) return found;
      }
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) {
        JSONObject found = findAwemeObject(array.opt(i), videoId);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static JSONObject extractAnyJson(String html) {
    JSONObject render = extractRenderData(html);
    if (render != null && looksLikeAwemePayload(render)) return render;
    Matcher matcher = Pattern.compile(
        "<script[^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html == null ? "" : html);
    while (matcher.find()) {
      String text = matcher.group(1);
      if (text == null || text.length() < 64) continue;
      if (!text.contains("aweme_id") && !text.contains("\"aweme\"")) continue;
      JSONObject parsed = parseJsonCandidate(text);
      if (parsed == null) {
        try {
          parsed = parseJsonCandidate(java.net.URLDecoder.decode(text, "UTF-8"));
        } catch (Exception ignored) {}
      }
      if (parsed != null && looksLikeAwemePayload(parsed)) return parsed;
      Matcher assign = Pattern.compile("=\\s*(\\{[\\s\\S]*\\})\\s*;?\\s*$").matcher(text.trim());
      if (assign.find()) {
        parsed = parseJsonCandidate(assign.group(1));
        if (parsed != null && looksLikeAwemePayload(parsed)) return parsed;
      }
    }
    return null;
  }

  /**
   * 判断一个 JSONObject 是否像作品数据结构,避免把页面里其他无关 JSON 误当作 aweme 数据。
   * 必须至少有一个能解析为数字/长整型字符串的 aweme_id 字段,
   * 并且在任意位置出现 video / images 字段(视频或图文作品必备)。
   */
  private static boolean looksLikeAwemePayload(JSONObject object) {
    if (object == null) return false;
    if (object.opt("aweme_id") != null || object.opt("awemeId") != null
        || object.opt("group_id") != null || object.opt("item_id") != null) {
      return hasMediaField(object);
    }
    JSONArray names = object.names();
    if (names == null) return false;
    for (int i = 0; i < names.length(); i++) {
      Object child = object.opt(names.optString(i));
      if (child instanceof JSONObject && looksLikeAwemePayload((JSONObject) child)) {
        return true;
      }
      if (child instanceof JSONArray) {
        JSONArray array = (JSONArray) child;
        for (int j = 0; j < array.length(); j++) {
          Object item = array.opt(j);
          if (item instanceof JSONObject && looksLikeAwemePayload((JSONObject) item)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean hasMediaField(JSONObject object) {
    if (object.has("video") || object.has("images")
        || object.has("images_list") || object.has("imagesList")) return true;
    JSONArray names = object.names();
    if (names == null) return false;
    for (int i = 0; i < names.length(); i++) {
      Object child = object.opt(names.optString(i));
      if (child instanceof JSONObject && hasMediaField((JSONObject) child)) return true;
    }
    return false;
  }

  private static JSONObject parseJsonCandidate(String text) {
    if (text == null) return null;
    try { return new JSONObject(text); } catch (Exception ignored) {}
    try { return new JSONObject(java.net.URLDecoder.decode(text, "UTF-8")); } catch (Exception ignored) {}
    return null;
  }

  private static String extractPlayUrlFromHtml(String html) {
    if (html == null) return null;
    Matcher matcher = Pattern.compile(
        "\\\"play_addr\\\"\\s*:\\s*\\{.*?\\\"url_list\\\"\\s*:\\s*\\[(.*?)\\]",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
    if (matcher.find()) {
      Matcher urlMatcher = Pattern.compile("\\\"(https?:.*?)(?<!\\\\)\\\"").matcher(matcher.group(1));
      while (urlMatcher.find()) {
        String url = unescapeJsonUrl(urlMatcher.group(1));
        if (isHttp(url)) return url;
      }
    }
    matcher = Pattern.compile("https?:\\\\/\\\\/[^\\\"']+(?:douyinvod|bytevcdn)[^\\\"']+",
        Pattern.CASE_INSENSITIVE).matcher(html);
    if (matcher.find()) return unescapeJsonUrl(matcher.group());
    return null;
  }

  private static String unescapeJsonUrl(String value) {
    return value.replace("\\\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
  }

  private static VideoItem parseAweme(JSONObject aweme, String sourceUrl, String resolvedUrl) throws Exception {
    String id = firstString(aweme, "aweme_id", "awemeId", "id", "group_id", "groupId", "item_id", "itemId");
    String title = firstString(aweme, "desc", "description");
    JSONObject author = aweme.optJSONObject("author");
    String authorName = author == null ? null : firstString(author, "nickname", "nick_name", "name");
    if (title == null || title.trim().isEmpty()) title = "douyin_" + (id == null ? "video" : id);

    long publishTime = firstLong(aweme, "create_time", "createTime", "publish_time", "publishTime");
    JSONArray images = firstArray(aweme, "images", "images_list", "imagesList");
    String contentType = images != null && images.length() > 0 ? "image" : "video";
    List<String> hashtags = extractHashtags(title, aweme);

    List<String> imageUrls = null;
    if (images != null && images.length() > 0) {
      imageUrls = new ArrayList<>();
      for (int i = 0; i < images.length(); i++) {
        JSONObject image = images.optJSONObject(i);
        if (image == null) continue;
        String url = firstUrlFromList(image, "url_list", "urlList");
        if (url != null) imageUrls.add(url);
      }
    }

    // 图文作品的 aweme 同时带 images 和 video（配乐流），必须优先图片分支
    if (imageUrls != null && !imageUrls.isEmpty()) {
      String cover = imageUrls.get(0);
      return new VideoItem(Platform.DOUYIN, sourceUrl, resolvedUrl, cleanTitle(title),
          null, cover, "已获取图文图片直链", authorName,
          publishTime, 0, "image", hashtags, id, imageUrls);
    }
    JSONObject video = aweme.optJSONObject("video");
    if (video == null) {
      throw new IllegalStateException("作品未包含视频或图片地址");
    }
    long rawDuration = firstLong(video, "duration", "duration_ms", "durationMs");
    long durationSeconds = rawDuration > 1000 ? Math.round(rawDuration / 1000.0d) : rawDuration;
    String media = bestVideoUrl(video);
    if (media == null) throw new IllegalStateException("作品未包含视频播放地址");
    String cover = firstUrl(video, "origin_cover", "originCover", "cover", "dynamic_cover", "dynamicCover");
    return new VideoItem(Platform.DOUYIN, sourceUrl, resolvedUrl, cleanTitle(title),
        normalizeMediaUrl(media), cover, "已获取视频直链", authorName,
        publishTime, durationSeconds, "video", hashtags, id, null);
  }

  private static List<String> extractHashtags(String title, JSONObject aweme) {
    List<String> result = new ArrayList<>();
    JSONArray textExtras = firstArray(aweme, "text_extra", "textExtra");
    if (textExtras != null) {
      for (int i = 0; i < textExtras.length(); i++) {
        JSONObject extra = textExtras.optJSONObject(i);
        String name = extra == null ? null : firstString(extra, "hashtag_name", "hashtagName", "hash_tag_name");
        if (name != null && !result.contains(name)) result.add(name);
      }
    }
    Matcher matcher = Pattern.compile("#([^#\\s,。!?、,;;::]+)").matcher(title == null ? "" : title);
    while (matcher.find()) {
      String name = matcher.group(1);
      if (!result.contains(name)) result.add(name);
    }
    return result;
  }

  private static long firstLong(JSONObject object, String... keys) {
    if (object == null) return 0;
    for (String key : keys) {
      Object value = object.opt(key);
      if (value instanceof Number) return ((Number) value).longValue();
      if (value instanceof String) {
        try { return Long.parseLong((String) value); } catch (Exception ignored) {}
      }
    }
    return 0;
  }

  private static String bestVideoUrl(JSONObject video) {
    JSONArray bitRates = video.optJSONArray("bit_rate");
    String best = null;
    long bestRate = -1;
    if (bitRates != null) {
      for (int i = 0; i < bitRates.length(); i++) {
        JSONObject item = bitRates.optJSONObject(i);
        if (item == null) continue;
        String url = firstUrl(item, "play_addr", "playAddr");
        long rate = item.optLong("bit_rate", item.optLong("bitRate", 0));
        if (url != null && rate >= bestRate) {
          best = url;
          bestRate = rate;
        }
      }
    }
    return best != null ? best : firstUrl(video, "play_addr", "playAddr", "download_addr");
  }

  private static String firstUrlFromList(JSONObject object, String... keys) {
    if (object == null) return null;
    for (String key : keys) {
      JSONArray urls = object.optJSONArray(key);
      if (urls != null) {
        for (int i = 0; i < urls.length(); i++) {
          String url = urls.optString(i, null);
          if (isHttp(url)) return url;
        }
      }
    }
    return null;
  }

  private static String firstUrl(JSONObject parent, String... keys) {
    for (String key : keys) {
      Object value = parent.opt(key);
      if (value instanceof JSONObject) {
        JSONArray urls = ((JSONObject) value).optJSONArray("url_list");
        if (urls == null) urls = ((JSONObject) value).optJSONArray("urlList");
        if (urls != null) {
          for (int i = 0; i < urls.length(); i++) {
            String url = urls.optString(i, null);
            if (isHttp(url)) return url;
          }
        }
      } else if (value instanceof String && isHttp((String) value)) {
        return (String) value;
      }
    }
    return null;
  }

  private static String followRedirects(String sourceUrl) throws Exception {
    String current = sourceUrl;
    for (int i = 0; i < 8; i++) {
      HttpURLConnection connection = open(current, sourceUrl);
      connection.setInstanceFollowRedirects(false);
      int status = connection.getResponseCode();
      if (status < 300 || status >= 400) {
        connection.disconnect();
        return current;
      }
      String location = connection.getHeaderField("Location");
      connection.disconnect();
      if (location == null) throw new IllegalStateException("抖音短链缺少跳转地址");
      current = new URL(new URL(current), location).toString();
      if (!Platform.DOUYIN.allowsUrl(current)) {
        throw new SecurityException("抖音短链跳转到非官方域名");
      }
    }
    throw new IllegalStateException("抖音短链跳转次数过多");
  }

  private static JSONObject requestJson(String url, String referer) throws Exception {
    String body = requestText(url, referer);
    if (body.trim().isEmpty()) throw new IllegalStateException("抖音接口返回空内容");
    try {
      return new JSONObject(body);
    } catch (Exception error) {
      throw new IllegalStateException("抖音接口未返回 JSON，可能触发了风控");
    }
  }

  private static String requestText(String url, String referer) throws Exception {
    HttpURLConnection connection = open(url, referer);
    int status = connection.getResponseCode();
    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    String body = read(stream, 8 * 1024 * 1024);
    connection.disconnect();
    if (status >= 400) throw new IllegalStateException("抖音接口 HTTP " + status);
    return body;
  }

  private static HttpURLConnection open(String rawUrl, String referer) throws Exception {
    URL url = new URL(rawUrl);
    if (!Platform.DOUYIN.allowsUrl(rawUrl)) throw new SecurityException("非抖音官方域名: " + url.getHost());
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(20000);
    connection.setRequestProperty("User-Agent", USER_AGENT);
    connection.setRequestProperty("Accept", "application/json,text/html,*/*");
    connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
    connection.setRequestProperty("Referer", referer == null ? "https://www.douyin.com/" : referer);
    String cookies = cookiesFor(rawUrl);
    if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
    return connection;
  }

  private static String cookiesFor(String url) {
    // 合并镜像文件与 CookieManager 两处来源,按 cookie 名去重。
    // 镜像可能只有部分条目(旧版被覆盖),而 WebView 会话里有完整登录态;
    // 反之 WebView 未初始化时镜像才是唯一来源。合并保证登录 cookie 不丢。
    java.util.Map<String, String> merged = new java.util.LinkedHashMap<>();
    String mirror = CookieStore.headerFor(Platform.DOUYIN, url);
    mergeCookies(merged, mirror);
    try {
      String web = CookieManager.getInstance().getCookie(url);
      if ((web == null || web.isEmpty()) && !url.contains("douyin.com")) {
        web = CookieManager.getInstance().getCookie("https://www.douyin.com/");
      }
      mergeCookies(merged, web);
    } catch (Exception ignored) {
    }
    if (merged.isEmpty()) return null;
    StringBuilder header = new StringBuilder();
    for (java.util.Map.Entry<String, String> entry : merged.entrySet()) {
      if (header.length() > 0) header.append("; ");
      header.append(entry.getKey()).append("=").append(entry.getValue());
    }
    return header.toString();
  }

  private static void mergeCookies(java.util.Map<String, String> target, String header) {
    if (header == null || header.isEmpty()) return;
    String[] parts = header.split(";");
    for (String part : parts) {
      String pair = part.trim();
      int equals = pair.indexOf('=');
      if (equals <= 0) continue;
      String name = pair.substring(0, equals).trim();
      String value = pair.substring(equals + 1).trim();
      if (name.isEmpty()) continue;
      target.put(name, value);
    }
  }

  private static String extractVideoId(String url) {
    for (Pattern pattern : VIDEO_ID_PATTERNS) {
      Matcher matcher = pattern.matcher(url);
      if (matcher.find()) return matcher.group(1);
    }
    return null;
  }

  private static String extractUserId(String url) {
    Matcher matcher = USER_ID_PATTERN.matcher(url);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static JSONArray firstArray(JSONObject object, String... keys) {
    for (String key : keys) {
      JSONArray value = object.optJSONArray(key);
      if (value != null) return value;
    }
    return null;
  }

  private static JSONArray findArray(Object value, String... keys) {
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      JSONArray direct = firstArray(object, keys);
      if (direct != null) return direct;
      JSONArray names = object.names();
      if (names != null) for (int i = 0; i < names.length(); i++) {
        JSONArray found = findArray(object.opt(names.optString(i)), keys);
        if (found != null) return found;
      }
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) {
        JSONArray found = findArray(array.opt(i), keys);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static JSONObject extractRenderData(String html) {
    Matcher matcher = Pattern.compile(
        "<script\\s+id=\\\"RENDER_DATA\\\"\\s+type=\\\"application/json\\\">(.+?)</script>",
        Pattern.DOTALL).matcher(html);
    if (!matcher.find()) return null;
    try {
      return new JSONObject(java.net.URLDecoder.decode(matcher.group(1), "UTF-8"));
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String firstString(JSONObject object, String... keys) {
    for (String key : keys) {
      String value = object.optString(key, null);
      if (value != null && !value.trim().isEmpty()) return value;
    }
    return null;
  }

  private static String normalizeMediaUrl(String url) {
    return url.replace("playwm", "play").replace("http://", "https://");
  }

  private static boolean isHttp(String value) {
    return value != null && (value.startsWith("https://") || value.startsWith("http://"));
  }

  private static String cleanTitle(String value) {
    String clean = value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim();
    return clean.length() > 100 ? clean.substring(0, 100) : clean;
  }

  private static String read(InputStream stream, int maxChars) throws Exception {
    if (stream == null) return "";
    StringBuilder result = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      char[] buffer = new char[4096];
      int count;
      while ((count = reader.read(buffer)) >= 0 && result.length() < maxChars) {
        result.append(buffer, 0, Math.min(count, maxChars - result.length()));
      }
    }
    return result.toString();
  }

  private static List<VideoItem> singleton(VideoItem item) {
    List<VideoItem> result = new ArrayList<>();
    result.add(item);
    return result;
  }
}
