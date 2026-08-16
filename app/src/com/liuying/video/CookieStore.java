package com.liuying.video;

import android.content.Context;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class CookieStore {
  private static volatile Context appContext;
  private static final String DIRECTORY = "cookies";
  private static final String[] DOUYIN_ORIGINS = {
      "https://www.douyin.com/", "https://www.iesdouyin.com/", "https://passport.douyin.com/"
  };
  private static final String[] KUAISHOU_ORIGINS = {
      "https://www.kuaishou.com/", "https://passport.kuaishou.com/"
  };
  private static final String[] XIAOHONGSHU_ORIGINS = {
      "https://www.xiaohongshu.com/", "https://edith.xiaohongshu.com/"
  };
  private static final String[] BILIBILI_ORIGINS = {
      "https://www.bilibili.com/", "https://passport.bilibili.com/", "https://api.bilibili.com/"
  };
  private static final String[] YOUTUBE_ORIGINS = {
      "https://www.youtube.com/", "https://accounts.google.com/"
  };

  private CookieStore() {}

  static synchronized void save(Context context, Platform platform) {
    if (platform == null || platform == Platform.UNKNOWN) return;
    int entryCount = 0;
    JSONArray entries = new JSONArray();
    try {
      CookieManager manager = CookieManager.getInstance();
      for (String origin : origins(platform)) {
        String header = manager.getCookie(origin);
        if (header == null || header.trim().isEmpty()) continue;
        JSONObject entry = new JSONObject();
        entry.put("origin", origin);
        entry.put("cookie", header);
        entries.put(entry);
        entryCount++;
      }
      // 防覆盖: CookieManager 会话丢失(WebView 未初始化/被清空)时,
      // 保留镜像中已有的登录 cookie,避免把 59 条覆盖成 3 条。
      if (entryCount == 0) {
        DiagnosticLog.w(context, "CookieStore", "save skipped platform=" + platform.name()
            + " entries=0,保留已有镜像");
        return;
      }
      if (mirrorHasSession(context, platform) && !containsSessionHint(entries.toString())) {
        DiagnosticLog.w(context, "CookieStore", "save skipped platform=" + platform.name()
            + " entries=" + entryCount + " 缺少登录态,保留已有镜像");
        return;
      }
      writeMirror(context, platform, entries);
      DiagnosticLog.i(context, "CookieStore", "save platform=" + platform.name()
          + " entries=" + entryCount);
    } catch (Exception exception) {
      DiagnosticLog.e(context, "CookieStore", "save failed platform=" + platform.name()
          + " entries=" + entryCount, diagnosticFailure(exception));
      // WebView 自身仍会持久化 Cookie;文件镜像失败不应中断登录。
    }
  }

  static synchronized void restore(Context context, Platform platform) {
    if (platform == null || platform == Platform.UNKNOWN) return;
    File source = file(context, platform);
    if (!source.isFile()) {
      DiagnosticLog.i(context, "CookieStore", "restore platform=" + platform.name()
          + " entries=0");
      return;
    }
    int restoredEntries = 0;
    try {
      byte[] bytes = readLimited(source, 1024 * 1024);
      JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      JSONArray entries = root.optJSONArray("entries");
      if (entries == null) throw new IllegalArgumentException("Cookie 条目缺失");
      CookieManager manager = CookieManager.getInstance();
      manager.setAcceptCookie(true);
      for (int i = 0; i < entries.length(); i++) {
        JSONObject entry = entries.optJSONObject(i);
        if (entry == null) continue;
        String origin = entry.optString("origin", "");
        String header = entry.optString("cookie", "");
        if (!platform.allowsUrl(origin) || header.isEmpty()) continue;
        boolean restored = false;
        String[] cookies = header.split(";\\s*");
        for (String cookie : cookies) {
          int equals = cookie.indexOf('=');
          if (equals <= 0) continue;
          manager.setCookie(origin, cookie + "; Path=/; Secure");
          restored = true;
        }
        if (restored) restoredEntries++;
      }
      manager.flush();
      DiagnosticLog.i(context, "CookieStore", "restore platform=" + platform.name()
          + " entries=" + restoredEntries);
    } catch (Exception exception) {
      DiagnosticLog.e(context, "CookieStore", "restore failed platform=" + platform.name()
          + " entries=" + restoredEntries, diagnosticFailure(exception));
      // 损坏或过期的镜像文件不影响 CookieManager 自带存储。
    }
  }

  static synchronized void clearAll(Context context) {
    try {
      CookieManager manager = CookieManager.getInstance();
      manager.removeAllCookies(null);
      manager.flush();
      File directory = directory(context);
      File[] files = directory.listFiles();
      if (files != null) for (File file : files) file.delete();
      DiagnosticLog.i(context, "CookieStore", "clearAll completed");
    } catch (Exception exception) {
      DiagnosticLog.e(context, "CookieStore", "clearAll failed", diagnosticFailure(exception));
    }
  }

  static synchronized boolean importFromJson(Context context, String jsonPath) {
    Platform platform = null;
    int importedEntries = 0;
    JSONArray mirrorEntries = new JSONArray();
    try {
      byte[] bytes = readLimited(new File(jsonPath), 2 * 1024 * 1024);
      JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      String platformName = root.optString("platform", "");
      for (Platform p : Platform.values()) {
        if (p.name().equalsIgnoreCase(platformName)) { platform = p; break; }
      }
      if (platform == null) throw new IllegalArgumentException("未知 Cookie 平台");
      JSONArray cookies = root.optJSONArray("cookies");
      if (cookies == null) throw new IllegalArgumentException("Cookie 条目缺失");
      CookieManager manager = CookieManager.getInstance();
      manager.setAcceptCookie(true);
      for (int i = 0; i < cookies.length(); i++) {
        JSONObject cookie = cookies.optJSONObject(i);
        if (cookie == null) continue;
        String name = cookie.optString("name", "").trim();
        String value = cookie.optString("value", "");
        String domain = cookie.optString("domain", "");
        if (name.isEmpty() || value.isEmpty() || domain.isEmpty()) continue;
        String origin = domain.startsWith(".") ? "https://" + domain.substring(1)
            : "https://" + domain;
        if (!platform.allowsUrl(origin)) continue;
        // 带 Domain 属性写入 CookieManager,提高 WebView 会话命中率。
        // 注意: CookieManager.setCookie 是异步的,flush 后仍需 WebView 初始化才真正生效。
        String domainAttr = domain.startsWith(".") ? domain : "." + domain;
        manager.setCookie(origin, name + "=" + value + "; Path=/; Domain=" + domainAttr
            + (cookie.optBoolean("secure", true) ? "; Secure" : ""));
        JSONObject mirrorEntry = new JSONObject();
        mirrorEntry.put("origin", origin);
        mirrorEntry.put("cookie", name + "=" + value);
        mirrorEntries.put(mirrorEntry);
        importedEntries++;
      }
      manager.flush();
      writeMirror(context, platform, mirrorEntries);
      DiagnosticLog.i(context, "CookieStore", "import platform=" + platform.name()
          + " entries=" + importedEntries);
      return true;
    } catch (Exception exception) {
      String platformLabel = platform == null ? Platform.UNKNOWN.name() : platform.name();
      DiagnosticLog.e(context, "CookieStore", "import failed platform=" + platformLabel
          + " entries=" + importedEntries, diagnosticFailure(exception));
      return false;
    }
  }

  static void init(Context context) {
    if (context != null) appContext = context.getApplicationContext();
  }

  /**
   * 直读镜像文件拼出 Cookie 头,不依赖 WebView CookieManager。
   * 这是解析器的主路径:即使从未打开过 WebView,导入的 cookie 也能立即生效。
   */
  static String headerFor(Platform platform, String url) {
    Context context = appContext;
    if (context == null || platform == null || platform == Platform.UNKNOWN) return null;
    File source = file(context, platform);
    if (!source.isFile()) return null;
    try {
      byte[] bytes = readLimited(source, 1024 * 1024);
      JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      JSONArray entries = root.optJSONArray("entries");
      if (entries == null || entries.length() == 0) return null;
      StringBuilder header = new StringBuilder();
      java.util.Set<String> seen = new java.util.LinkedHashSet<>();
      for (int i = 0; i < entries.length(); i++) {
        JSONObject entry = entries.optJSONObject(i);
        if (entry == null) continue;
        String origin = entry.optString("origin", "");
        String cookie = entry.optString("cookie", "");
        if (cookie.isEmpty()) continue;
        // 镜像里 cookie 可能是 "name=value" 或 "name=value; Path=/; Secure"
        String[] parts = cookie.split(";");
        for (String part : parts) {
          String pair = part.trim();
          if (pair.isEmpty() || !pair.contains("=")) continue;
          String name = pair.substring(0, pair.indexOf('=')).trim();
          if (name.isEmpty() || seen.contains(name)) continue;
          seen.add(name);
          if (header.length() > 0) header.append("; ");
          header.append(pair);
        }
      }
      return header.length() == 0 ? null : header.toString();
    } catch (Exception ignored) {
      return null;
    }
  }

  static String cookieHeader(Context context, Platform platform, String url) {
    restore(context, platform);
    return CookieManager.getInstance().getCookie(url);
  }

  /**
   * 当前应用内是否存在该平台的有效 Cookie。
   * 优先读取 CookieManager 单例,避免对每个平台多读一次;否则回退到上次 save 的镜像文件。
   */
  static synchronized boolean hasCookie(Context context, Platform platform) {
    if (platform == null || platform == Platform.UNKNOWN) return false;
    CookieManager manager = CookieManager.getInstance();
    for (String origin : origins(platform)) {
      String header = manager.getCookie(origin);
      if (containsSessionHint(header)) return true;
    }
    return mirrorHasSession(context, platform);
  }

  /**
   * 供 UI 展示用的简短状态描述。
   * 区分"未导入 / 已导入但可能已过期 / 看起来已登录"。
   */
  static synchronized String statusText(Context context, Platform platform) {
    if (platform == null || platform == Platform.UNKNOWN) return "不支持";
    CookieManager manager = CookieManager.getInstance();
    for (String origin : origins(platform)) {
      String header = manager.getCookie(origin);
      if (containsLoginMarker(header)) return "已登录";
    }
    if (mirrorHasSession(context, platform)) return "已导入(可能已过期)";
    return "未登录";
  }

  private static boolean containsSessionHint(String header) {
    if (header == null || header.trim().isEmpty()) return false;
    String lower = header.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("sessionid") || lower.contains("sid_tt")
        || lower.contains("ttwid") || lower.contains("uid_tt")
        || lower.contains("login") || lower.contains("user_unique_id");
  }

  private static boolean containsLoginMarker(String header) {
    if (header == null || header.trim().isEmpty()) return false;
    String lower = header.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("sessionid") || lower.contains("sessionid_ss")
        || lower.contains("sid_tt") || lower.contains("sid_ucp")
        || lower.contains("uid_tt") || lower.contains("user_unique_id")
        || lower.contains("passport_csrf_token");
  }

  private static boolean mirrorHasSession(Context context, Platform platform) {
    File source = file(context, platform);
    if (!source.isFile() || source.length() <= 0) return false;
    try {
      byte[] bytes = readLimited(source, 64 * 1024);
      JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      JSONArray entries = root.optJSONArray("entries");
      if (entries == null || entries.length() == 0) return false;
      for (int i = 0; i < entries.length(); i++) {
        JSONObject entry = entries.optJSONObject(i);
        if (entry == null) continue;
        if (containsSessionHint(entry.optString("cookie", ""))) return true;
      }
    } catch (Exception ignored) {}
    return false;
  }

  private static void writeMirror(Context context, Platform platform, JSONArray entries) throws Exception {
    JSONObject root = new JSONObject();
    root.put("platform", platform.name());
    root.put("savedAt", System.currentTimeMillis());
    root.put("entries", entries);
    File directory = directory(context);
    if (!directory.exists() && !directory.mkdirs()) {
      throw new IllegalStateException("无法创建 Cookie 目录");
    }
    File target = file(context, platform);
    File temporary = new File(directory, target.getName() + ".tmp");
    try (FileOutputStream output = new FileOutputStream(temporary)) {
      output.write(root.toString().getBytes(StandardCharsets.UTF_8));
      output.flush();
    }
    if (target.exists() && !target.delete()) {
      throw new IllegalStateException("无法更新 Cookie 文件");
    }
    if (!temporary.renameTo(target)) {
      throw new IllegalStateException("无法保存 Cookie 文件");
    }
  }

  private static File directory(Context context) {
    return new File(context.getFilesDir(), DIRECTORY);
  }

  private static File file(Context context, Platform platform) {
    return new File(directory(context), platform.name().toLowerCase(java.util.Locale.ROOT) + ".json");
  }

  private static String[] origins(Platform platform) {
    if (platform == Platform.DOUYIN) return DOUYIN_ORIGINS;
    if (platform == Platform.KUAISHOU) return KUAISHOU_ORIGINS;
    if (platform == Platform.XIAOHONGSHU) return XIAOHONGSHU_ORIGINS;
    if (platform == Platform.BILIBILI) return BILIBILI_ORIGINS;
    if (platform == Platform.YOUTUBE) return YOUTUBE_ORIGINS;
    return new String[0];
  }

  private static Exception diagnosticFailure(Exception exception) {
    Exception sanitized = new Exception("type=" + exception.getClass().getName());
    sanitized.setStackTrace(exception.getStackTrace());
    return sanitized;
  }

  private static byte[] readLimited(File file, int maximum) throws Exception {
    long length = file.length();
    if (length < 0 || length > maximum) throw new IllegalStateException("Cookie 文件过大");
    byte[] data = new byte[(int) length];
    try (FileInputStream input = new FileInputStream(file)) {
      int offset = 0;
      while (offset < data.length) {
        int count = input.read(data, offset, data.length - offset);
        if (count < 0) break;
        offset += count;
      }
      if (offset != data.length) throw new IllegalStateException("Cookie 文件读取不完整");
    }
    return data;
  }
}
