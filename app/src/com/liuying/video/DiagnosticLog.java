package com.liuying.video;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, best-effort diagnostic log with conservative redaction. */
public final class DiagnosticLog {
  private static final Object LOCK = new Object();
  private static final Charset UTF_8 = Charset.forName("UTF-8");
  private static final long MAX_BYTES = 512L * 1024L;
  private static final int MAX_READ_BYTES = 1024 * 1024;
  private static final int MAX_CAUSES = 4;
  private static final int MAX_STACK_LINES = 12;
  private static final Pattern URL = Pattern.compile(
      "(?i)\\b((?:https?|rtsp|ftp)://[^\\s<>\\\"]+)");
  private static final Pattern SENSITIVE_HEADER = Pattern.compile(
      "(?i)(\\b(?:cookie|authorization|set-cookie)\\s*[:=]\\s*)([^\\s,;]+(?:\\s+[^\\r\\n]*)?)");
  private static final Pattern SENSITIVE_PARAM = Pattern.compile(
      "(?i)([?&](?:token|access_token|refresh_token|id_token|signature|sig|sign|key|api_key|password|passwd|pwd|secret)=)([^&#\\s]*)");
  private static final Pattern LONG_SECRET = Pattern.compile(
      "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{81,}(?![A-Za-z0-9_-])");

  private DiagnosticLog() {
  }

  public static void i(Context context, String tag, String message) {
    write(context, "I", tag, message, null);
  }

  public static void w(Context context, String tag, String message) {
    write(context, "W", tag, message, null);
  }

  public static void e(Context context, String tag, String message) {
    write(context, "E", tag, message, null);
  }

  public static void e(Context context, String tag, String message, Throwable error) {
    write(context, "E", tag, message, error);
  }

  public static File file(Context context) {
    if (context == null) {
      return null;
    }
    try {
      return new File(new File(context.getFilesDir(), "logs"), "diagnostic.log");
    } catch (Throwable ignored) {
      return null;
    }
  }

  public static String read(Context context) {
    File target = file(context);
    if (target == null) {
      return "";
    }
    synchronized (LOCK) {
      FileInputStream input = null;
      try {
        input = new FileInputStream(target);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int remaining = MAX_READ_BYTES;
        int count;
        while (remaining > 0 && (count = input.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
          output.write(buffer, 0, count);
          remaining -= count;
        }
        return new String(output.toByteArray(), UTF_8);
      } catch (Throwable ignored) {
        return "";
      } finally {
        close(input);
      }
    }
  }

  public static void clear(Context context) {
    File target = file(context);
    if (target == null) {
      return;
    }
    synchronized (LOCK) {
      try {
        if (target.exists()) {
          new FileOutputStream(target, false).close();
        }
        File previous = new File(target.getParentFile(), "diagnostic-prev.log");
        if (previous.exists()) {
          previous.delete();
        }
      } catch (Throwable ignored) {
        // Diagnostics must never affect the caller.
      }
    }
  }

  private static void write(Context context, String level, String tag, String message,
      Throwable error) {
    File target = file(context);
    if (target == null) {
      return;
    }
    StringBuilder line = new StringBuilder();
    line.append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
    line.append(' ').append(level).append(' ').append(redact(tag));
    line.append(": ").append(redact(message));
    if (error != null) {
      appendThrowable(line, error);
    }
    line.append('\n');
    byte[] data = line.toString().getBytes(UTF_8);
    synchronized (LOCK) {
      FileOutputStream output = null;
      try {
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
          return;
        }
        if (target.exists() && target.length() + data.length > MAX_BYTES) {
          File previous = new File(parent, "diagnostic-prev.log");
          if (previous.exists() && !previous.delete()) {
            return;
          }
          if (!target.renameTo(previous)) {
            return;
          }
        }
        output = new FileOutputStream(target, true);
        output.write(data);
        output.flush();
      } catch (Throwable ignored) {
        // Diagnostics must never affect the caller.
      } finally {
        close(output);
      }
    }
  }

  private static void appendThrowable(StringBuilder out, Throwable error) {
    Throwable current = error;
    int causeCount = 0;
    while (current != null && causeCount < MAX_CAUSES) {
      out.append(" | exception=").append(redact(current.getClass().getName()));
      out.append(" message=").append(redact(current.getMessage()));
      StackTraceElement[] trace = current.getStackTrace();
      int lines = Math.min(MAX_STACK_LINES, trace == null ? 0 : trace.length);
      for (int i = 0; i < lines; i++) {
        out.append(" | at ").append(redact(String.valueOf(trace[i])));
      }
      current = current.getCause();
      causeCount++;
    }
    if (current != null) {
      out.append(" | cause-chain-truncated");
    }
  }

  private static String redact(String value) {
    if (value == null) {
      return "null";
    }
    String result = value.replace('\r', ' ').replace('\n', ' ');
    result = replaceUrl(result);
    result = replaceSensitive(SENSITIVE_HEADER, result);
    result = replaceSensitive(SENSITIVE_PARAM, result);
    return LONG_SECRET.matcher(result).replaceAll("[REDACTED]");
  }

  private static String replaceUrl(String value) {
    Matcher matcher = URL.matcher(value);
    StringBuffer output = new StringBuffer();
    while (matcher.find()) {
      String url = matcher.group(1);
      int query = url.indexOf('?');
      int fragment = url.indexOf('#');
      int cut = query >= 0 ? query : (fragment >= 0 ? fragment : url.length());
      if (fragment >= 0 && fragment < cut) {
        cut = fragment;
      }
      String replacement = url.substring(0, cut) + (cut < url.length() ? "[REDACTED]" : "");
      matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(output);
    return output.toString();
  }

  private static String replaceSensitive(Pattern pattern, String value) {
    Matcher matcher = pattern.matcher(value);
    StringBuffer output = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group(1) + "[REDACTED]"));
    }
    matcher.appendTail(output);
    return output.toString();
  }

  private static void close(FileInputStream stream) {
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException ignored) {
      }
    }
  }

  private static void close(FileOutputStream stream) {
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException ignored) {
      }
    }
  }
}
