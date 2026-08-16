package com.liuying.video;

import java.net.URI;
import java.util.Locale;

enum Platform {
  DOUYIN("抖音", "https://www.douyin.com/?showTab=login", new String[] {
      "douyin.com", "iesdouyin.com", "amemv.com", "douyinvod.com",
      "douyinpic.com", "douyinstatic.com", "byteimg.com", "bytecdn.cn",
      "bytedance.com", "snssdk.com", "douyinpassport.com"
  }),
  KUAISHOU("快手", "https://www.kuaishou.com/", new String[] {
      "kuaishou.com", "gifshow.com", "kwaicdn.com", "kwai.com",
      "ksapisrv.com", "yximgs.com"
  }),
  XIAOHONGSHU("小红书", "https://www.xiaohongshu.com/", new String[] {
      "xiaohongshu.com", "xhslink.com", "xhscdn.com", "xhscdn.net"
  }),
  BILIBILI("B站", "https://www.bilibili.com/", new String[] {
      "bilibili.com", "b23.tv", "bilivideo.com", "hdslb.com",
      "biliapi.com", "bilivideo.cn"
  }),
  YOUTUBE("YouTube", "https://www.youtube.com/", new String[] {
      "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com",
      "ytimg.com", "ggpht.com", "googleusercontent.com", "accounts.google.com",
      "google.com", "gstatic.com", "googleapis.com"
  }),
  UNKNOWN("未知", "", new String[0]);

  final String displayName;
  final String loginUrl;
  private final String[] allowedDomains;

  Platform(String displayName, String loginUrl, String[] allowedDomains) {
    this.displayName = displayName;
    this.loginUrl = loginUrl;
    this.allowedDomains = allowedDomains;
  }

  static Platform detect(String rawUrl) {
    try {
      String host = new URI(rawUrl).getHost();
      if (host == null) return UNKNOWN;
      host = host.toLowerCase(Locale.ROOT);
      for (Platform platform : values()) {
        if (platform.allowsHost(host)) return platform;
      }
    } catch (Exception ignored) {
    }
    return UNKNOWN;
  }

  static boolean isSupported(String rawUrl) {
    return detect(rawUrl) != UNKNOWN;
  }

  boolean allowsUrl(String rawUrl) {
    try {
      return allowsHost(new URI(rawUrl).getHost());
    } catch (Exception ignored) {
      return false;
    }
  }

  private boolean allowsHost(String host) {
    if (host == null) return false;
    host = host.toLowerCase(Locale.ROOT);
    for (String domain : allowedDomains) {
      if (matches(host, domain)) return true;
    }
    return false;
  }

  private static boolean matches(String host, String domain) {
    return host.equals(domain) || host.endsWith("." + domain);
  }
}
