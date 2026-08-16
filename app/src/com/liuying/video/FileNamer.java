package com.liuying.video;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.regex.Pattern;

final class FileNamer {
  static final String PREFS = "download_preferences";
  static final String KEY_REMOVE_HASHTAGS = "remove_hashtags";
  static final String KEY_ADD_SEQUENCE = "add_sequence_number";
  static final String KEY_DOWNLOAD_COVER = "download_cover";
  static final String KEY_AUTHOR_FOLDER = "author_folder";
  static final String KEY_MAX_LENGTH = "max_filename_length";
  private static final Pattern HASHTAG = Pattern.compile("#[^\\s#]+");

  private FileNamer() {}

  static String create(Context context, VideoItem item, int sequence) {
    SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    String title = value(item.title, "video");
    if (preferences.getBoolean(KEY_REMOVE_HASHTAGS, true)) {
      title = HASHTAG.matcher(title).replaceAll("").replaceAll("\\s+", " ").trim();
    }
    title = sanitize(title);
    int maxLength = Math.max(20, Math.min(200, preferences.getInt(KEY_MAX_LENGTH, 100)));
    if (title.length() > maxLength) title = title.substring(0, maxLength).trim();
    if (preferences.getBoolean(KEY_ADD_SEQUENCE, true)) {
      title = Math.max(1, sequence) + "." + title;
    }
    return title.isEmpty() ? "video_" + System.currentTimeMillis() : title;
  }

  static String authorFolder(Context context, VideoItem item) {
    SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    if (!preferences.getBoolean(KEY_AUTHOR_FOLDER, true)) return null;
    return sanitize(value(item.authorName, "unknown"));
  }

  static boolean downloadCover(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_DOWNLOAD_COVER, true);
  }

  static String sanitize(String value) {
    String clean = value.replaceAll("[\\x00-\\x1f\\x7f-\\x9f\\\\/:*?\"<>|\\r\\n\\t]", " ")
        .replaceAll("\\s+", " ").trim().replaceAll("[ .]+$", "");
    return clean.isEmpty() ? "unknown" : clean;
  }

  private static String value(String value, String fallback) {
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }
}
