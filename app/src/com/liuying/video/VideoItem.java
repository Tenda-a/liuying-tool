package com.liuying.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VideoItem {
  final Platform platform;
  final String sourceUrl;
  final String resolvedUrl;
  final String title;
  final String mediaUrl;
  final String coverUrl;
  final String message;
  final String authorName;
  final long publishTime;
  final long durationSeconds;
  final String contentType;
  final List<String> hashtags;
  final String videoId;
  final List<String> imageUrls;

  VideoItem(Platform platform, String sourceUrl, String resolvedUrl, String title,
            String mediaUrl, String coverUrl, String message) {
    this(platform, sourceUrl, resolvedUrl, title, mediaUrl, coverUrl, message,
        null, 0, 0, "video", null, null, null);
  }

  VideoItem(Platform platform, String sourceUrl, String resolvedUrl, String title,
            String mediaUrl, String coverUrl, String message, String authorName,
            long publishTime, long durationSeconds, String contentType,
            List<String> hashtags, String videoId, List<String> imageUrls) {
    this.platform = platform;
    this.sourceUrl = sourceUrl;
    this.resolvedUrl = resolvedUrl;
    this.title = title;
    this.mediaUrl = mediaUrl;
    this.coverUrl = coverUrl;
    this.message = message;
    this.authorName = authorName;
    this.publishTime = publishTime;
    this.durationSeconds = durationSeconds;
    this.contentType = contentType == null ? "video" : contentType;
    this.hashtags = hashtags == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(new ArrayList<>(hashtags));
    this.videoId = videoId;
    this.imageUrls = imageUrls == null
        ? Collections.<String>emptyList()
        : Collections.unmodifiableList(new ArrayList<>(imageUrls));
  }

  boolean canDownload() {
    if (mediaUrl != null
        && (mediaUrl.startsWith("https://") || mediaUrl.startsWith("http://"))) {
      return true;
    }
    return "image".equals(contentType) && !imageUrls.isEmpty();
  }

  boolean canDownloadImages() {
    return "image".equals(contentType) && !imageUrls.isEmpty();
  }
}
