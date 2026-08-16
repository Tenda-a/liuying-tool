package com.liuying.video;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DownloadController {
  static final int REQUEST_FOLDER = 1002;
  private static final String LOG_TAG = "Download";
  private static final String PREFS = "download_preferences";
  private static final String PREF_FOLDER_URI = "folder_uri";
  private static final String DEFAULT_FOLDER = "视频下载";
  private final Activity activity;
  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final Map<Long, String> pendingDownloads = new HashMap<>();
  private final Map<VideoItem, ProgressBar> progressBars = new HashMap<>();
  private TextView status;
  private TextView folderLabel;
  private BroadcastReceiver receiver;

  DownloadController(Activity activity) {
    this.activity = activity;
    registerReceiver();
  }

  void attachViews(TextView status, TextView folderLabel) {
    this.status = status;
    this.folderLabel = folderLabel;
    folderLabel.setText(folderLabel());
  }

  String folderLabel() {
    return preferences().getString(PREF_FOLDER_URI, null) == null
        ? "保存位置：Download/" + DEFAULT_FOLDER + "/"
        : "保存位置：自定义文件夹";
  }

  void chooseFolder() {
    new AlertDialog.Builder(activity)
        .setTitle("保存位置")
        .setItems(new String[] {"选择自定义文件夹", "恢复默认 Download/视频下载/"}, new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            if (which == 0) openFolderPicker();
            else {
              preferences().edit().remove(PREF_FOLDER_URI).apply();
              if (folderLabel != null) folderLabel.setText(folderLabel());
              Toast.makeText(activity, "已恢复默认保存位置", Toast.LENGTH_SHORT).show();
            }
          }
        })
        .show();
  }

  private void openFolderPicker() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    activity.startActivityForResult(intent, REQUEST_FOLDER);
  }

  boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode != REQUEST_FOLDER) return false;
    if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
      Uri uri = data.getData();
      int flags = data.getFlags()
          & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      try {
        activity.getContentResolver().takePersistableUriPermission(uri, flags);
        preferences().edit().putString(PREF_FOLDER_URI, uri.toString()).apply();
        if (folderLabel != null) folderLabel.setText(folderLabel());
        Toast.makeText(activity, "保存位置已更新", Toast.LENGTH_SHORT).show();
      } catch (Exception error) {
        Toast.makeText(activity, "无法保存目录权限：" + error.getMessage(), Toast.LENGTH_LONG).show();
      }
    }
    return true;
  }

  void enqueue(VideoItem item, int sequence) {
    enqueue(item, sequence, null);
  }

  static final int REQUEST_STORAGE = 1003;

  void enqueue(VideoItem item, int sequence, ProgressBar progressBar) {
    DiagnosticLog.i(activity, LOG_TAG, "enqueue platform=" + item.platform
        + " contentType=" + item.contentType + " sourceUrl=" + item.sourceUrl);
    if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
        && preferences().getString(PREF_FOLDER_URI, null) == null
        && activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      if (progressBar != null) progressBar.setVisibility(android.view.View.GONE);
      // manifest 未声明时 requestPermissions 会被直接拒绝，因此同时引导用户选 SAF 文件夹兼容
      try {
        activity.requestPermissions(
            new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
      } catch (Exception ignored) {}
      Toast.makeText(activity,
          "需要存储权限才能下载；若无法授权，请在保存位置选择自定义文件夹",
          Toast.LENGTH_LONG).show();
      return;
    }
    if (progressBar != null) {
      progressBar.setIndeterminate(false);
      progressBar.setMax(100);
      progressBar.setProgress(0);
      progressBars.put(item, progressBar);
    }
    String fileName = FileNamer.create(activity, item, sequence);
    String authorFolder = FileNamer.authorFolder(activity, item);
    if (item.canDownloadImages()) {
      downloadImages(item, fileName, authorFolder, progressBar);
      return;
    }
    String folderUri = preferences().getString(PREF_FOLDER_URI, null);
    if (folderUri == null) downloadToDefaultFolder(item, fileName, authorFolder);
    else downloadToTree(item, Uri.parse(folderUri), fileName, authorFolder);
  }


  private void downloadToDefaultFolder(final VideoItem item, final String fileName, final String authorFolder) {
    DiagnosticLog.i(activity, LOG_TAG, "default directory download started");
    showProgress(item, 0, "正在下载 0% · 0 B");
    executor.submit(new Runnable() {
      @Override public void run() {
        HttpURLConnection connection = null;
        Uri outputUri = null;
        File legacyFile = null;
        try {
          connection = openMediaConnection(item);
          int response = connection.getResponseCode();
          if (response < 200 || response >= 300) throw new IllegalStateException("HTTP " + response);
          long total = Build.VERSION.SDK_INT >= 24
              ? connection.getContentLengthLong() : connection.getContentLength();
          OutputStream output;
          if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                defaultRelativePath(authorFolder));
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            outputUri = insertDownload(values, fileName + ".mp4");
            if (outputUri == null) throw new IllegalStateException("无法创建下载文件");
            output = activity.getContentResolver().openOutputStream(outputUri, "w");
          } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DEFAULT_FOLDER
                    + (authorFolder == null ? "" : "/" + authorFolder));
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建下载目录");
            legacyFile = new File(directory, fileName + ".mp4");
            output = new FileOutputStream(legacyFile);
          }
          if (output == null) throw new IllegalStateException("无法写入下载文件");
          copyResponse(item, connection, output, total);
          if (Build.VERSION.SDK_INT >= 29 && outputUri != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            activity.getContentResolver().update(outputUri, done, null, null);
          }
          if (FileNamer.downloadCover(activity) && isHttp(item.coverUrl)
              && !"image".equals(item.contentType)) {
            try { downloadCoverToDefault(item, fileName, authorFolder); }
            catch (Exception ignored) {}
          }
          DiagnosticLog.i(activity, LOG_TAG, "default directory download completed");
          activity.runOnUiThread(new Runnable() {
            @Override public void run() {
              showProgress(item, 100, "下载完成 · 100%");
              progressBars.remove(item);
              Toast.makeText(activity, "下载完成：" + fileName + ".mp4", Toast.LENGTH_LONG).show();
            }
          });
        } catch (final Exception error) {
          if (outputUri != null) {
            try { activity.getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
          }
          if (legacyFile != null && legacyFile.exists()) legacyFile.delete();
          notifyFailure(item, error);
        } finally {
          if (connection != null) connection.disconnect();
        }
      }
    });
  }

  private void enqueueSystemDownload(VideoItem item) {
    String fileName = FileNamer.create(activity, item, 1) + ".mp4";
    String relativePath = "Download/" + DEFAULT_FOLDER + "/" + fileName;
    try {
      DownloadManager.Request request = new DownloadManager.Request(Uri.parse(item.mediaUrl))
          .setTitle(item.title)
          .setDescription("保存到 Download/" + DEFAULT_FOLDER)
          .setMimeType("video/mp4")
          .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
          .setDestinationInExternalPublicDir(
              Environment.DIRECTORY_DOWNLOADS, DEFAULT_FOLDER + "/" + fileName);
      addHeaders(request, item);
      DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
      long id = manager.enqueue(request);
      pendingDownloads.put(id, relativePath);
      showStatus("正在下载到 Download/" + DEFAULT_FOLDER + "/");
      Toast.makeText(activity, "开始下载：" + fileName, Toast.LENGTH_SHORT).show();
    } catch (Exception error) {
      DiagnosticLog.e(activity, LOG_TAG, "system download enqueue failed", error);
      Toast.makeText(activity, "无法创建下载任务：" + error.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void downloadToTree(final VideoItem item, final Uri treeUri, final String fileName, final String authorFolder) {
    DiagnosticLog.i(activity, LOG_TAG, "SAF download started");
    showProgress(item, 0, "正在下载 0% · 0 B");
    executor.submit(new Runnable() {
      @Override public void run() {
        HttpURLConnection connection = null;
        Uri documentUri = null;
        try {
          Uri parent = resolveTreeParent(treeUri, authorFolder);
          documentUri = DocumentsContract.createDocument(
              activity.getContentResolver(), parent, "video/mp4", fileName + ".mp4");
          if (documentUri == null) throw new IllegalStateException("无法在所选文件夹创建文件");
          connection = openMediaConnection(item);
          int response = connection.getResponseCode();
          if (response < 200 || response >= 300) throw new IllegalStateException("HTTP " + response);
          long total = Build.VERSION.SDK_INT >= 24
              ? connection.getContentLengthLong() : connection.getContentLength();
          OutputStream output = activity.getContentResolver().openOutputStream(documentUri, "w");
          if (output == null) throw new IllegalStateException("无法写入所选文件夹");
          copyResponse(item, connection, output, total);
          if (FileNamer.downloadCover(activity) && isHttp(item.coverUrl)
              && !"image".equals(item.contentType)) {
            try { downloadCoverToTree(item, parent, fileName); }
            catch (Exception ignored) {}
          }
          DiagnosticLog.i(activity, LOG_TAG, "SAF download completed");
          activity.runOnUiThread(new Runnable() {
            @Override public void run() {
              showProgress(item, 100, "下载完成 · 100%");
              progressBars.remove(item);
              Toast.makeText(activity, "下载完成：" + fileName + ".mp4", Toast.LENGTH_LONG).show();
            }
          });
        } catch (final Exception error) {
          if (documentUri != null) {
            try { DocumentsContract.deleteDocument(activity.getContentResolver(), documentUri); }
            catch (Exception ignored) {}
          }
          notifyFailure(item, error);
        } finally {
          if (connection != null) connection.disconnect();
        }
      }
    });
  }

  private void downloadImages(final VideoItem item, final String fileName,
      final String authorFolder, final ProgressBar progressBar) {
    final int total = item.imageUrls.size();
    final String folderUri = preferences().getString(PREF_FOLDER_URI, null);
    DiagnosticLog.i(activity, LOG_TAG, "gallery download started destination="
        + (folderUri == null ? "default" : "SAF") + " imageCount=" + total);
    showProgress(item, 0, "正在下载 0/" + total);
    executor.submit(new Runnable() {
      @Override public void run() {
        Uri treeParent = null;
        if (folderUri != null) {
          try {
            treeParent = resolveTreeParent(Uri.parse(folderUri), authorFolder);
          } catch (final Exception error) {
            notifyFailure(item, error);
            return;
          }
        }
        int completed = 0;
        for (int i = 0; i < total; i++) {
          String imageUrl = item.imageUrls.get(i);
          String imageFileName = fileName + "_" + (i + 1) + ".jpg";
          HttpURLConnection connection = null;
          Uri outputUri = null;
          File legacyFile = null;
          try {
            connection = openMediaConnection(item, imageUrl);
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new IllegalStateException("HTTP " + response);
            OutputStream output;
            if (folderUri == null) {
              if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Downloads.RELATIVE_PATH, defaultRelativePath(authorFolder));
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                outputUri = insertDownload(values, imageFileName);
                if (outputUri == null) throw new IllegalStateException("无法创建下载文件");
                output = activity.getContentResolver().openOutputStream(outputUri, "w");
              } else {
                File directory = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), DEFAULT_FOLDER
                        + (authorFolder == null ? "" : "/" + authorFolder));
                if (!directory.exists() && !directory.mkdirs())
                  throw new IllegalStateException("无法创建下载目录");
                legacyFile = new File(directory, imageFileName);
                output = new FileOutputStream(legacyFile);
              }
            } else {
              outputUri = DocumentsContract.createDocument(
                  activity.getContentResolver(), treeParent, "image/jpeg", imageFileName);
              if (outputUri == null) throw new IllegalStateException("无法在所选文件夹创建文件");
              output = activity.getContentResolver().openOutputStream(outputUri, "w");
            }
            if (output == null) throw new IllegalStateException("无法写入下载文件");
            copyRaw(connection.getInputStream(), output);
            if (Build.VERSION.SDK_INT >= 29 && folderUri == null && outputUri != null) {
              ContentValues done = new ContentValues();
              done.put(MediaStore.Downloads.IS_PENDING, 0);
              activity.getContentResolver().update(outputUri, done, null, null);
            }
            completed++;
            final int doneCount = completed;
            final int percent = doneCount * 100 / total;
            showProgress(item, percent, "正在下载图片 " + doneCount + "/" + total + " · " + percent + "%");
          } catch (final Exception error) {
            if (outputUri != null) {
              try {
                if (folderUri == null) activity.getContentResolver().delete(outputUri, null, null);
                else DocumentsContract.deleteDocument(activity.getContentResolver(), outputUri);
              } catch (Exception ignored) {}
            }
            if (legacyFile != null && legacyFile.exists()) legacyFile.delete();
            notifyFailure(item, error);
            return;
          } finally {
            if (connection != null) connection.disconnect();
          }
        }
        DiagnosticLog.i(activity, LOG_TAG, "gallery download completed destination="
            + (folderUri == null ? "default" : "SAF") + " imageCount=" + total);
        activity.runOnUiThread(new Runnable() {
          @Override public void run() {
            showProgress(item, 100, "下载完成 · " + total + " 张图片 · 100%");
            progressBars.remove(item);
            Toast.makeText(activity, "下载完成 · " + total + " 张图片", Toast.LENGTH_LONG).show();
          }
        });
      }
    });
  }

  private HttpURLConnection openMediaConnection(VideoItem item) throws Exception {
    return openMediaConnection(item, item.mediaUrl);
  }

  private HttpURLConnection openMediaConnection(VideoItem item, String rawUrl) throws Exception {
    URL current = new URL(upgradeToHttps(rawUrl));
    for (int i = 0; i < 5; i++) {
      HttpURLConnection connection = (HttpURLConnection) current.openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(30000);
      connection.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(activity));
      connection.setRequestProperty("Referer", item.resolvedUrl == null ? item.sourceUrl : item.resolvedUrl);
      connection.setRequestProperty("Accept", "*/*");
      connection.setRequestProperty("Connection", "keep-alive");
      String cookies = CookieStore.cookieHeader(activity, item.platform, current.toString());
      if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
      int code = connection.getResponseCode();
      if (code >= 300 && code < 400) {
        String location = connection.getHeaderField("Location");
        connection.disconnect();
        if (location == null || location.length() == 0) throw new IllegalStateException("下载跳转缺少 Location");
        current = new URL(upgradeToHttps(new URL(current, location).toString()));
        continue;
      }
      return connection;
    }
    throw new IllegalStateException("下载跳转次数过多");
  }

  private Uri insertDownload(ContentValues values, String displayName) {
    values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
    Uri uri = activity.getContentResolver().insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    if (uri != null) return uri;
    // 同名冲突或索引异常时换唯一文件名重试一次
    int dot = displayName.lastIndexOf('.');
    String base = dot > 0 ? displayName.substring(0, dot) : displayName;
    String ext = dot > 0 ? displayName.substring(dot) : "";
    values.put(MediaStore.Downloads.DISPLAY_NAME,
        base + "_" + System.currentTimeMillis() + ext);
    return activity.getContentResolver().insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
  }

  private String defaultRelativePath(String authorFolder) {
    return Environment.DIRECTORY_DOWNLOADS + "/" + DEFAULT_FOLDER
        + (authorFolder == null ? "" : "/" + authorFolder);
  }

  private void downloadCoverToDefault(VideoItem item, String fileName, String authorFolder) throws Exception {
    HttpURLConnection cover = null;
    Uri uri = null;
    File file = null;
    try {
      cover = openMediaConnection(item, item.coverUrl);
      int response = cover.getResponseCode();
      if (response < 200 || response >= 300) throw new IllegalStateException("封面 HTTP " + response);
      OutputStream output;
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Downloads.RELATIVE_PATH, defaultRelativePath(authorFolder));
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        uri = insertDownload(values, fileName + ".jpg");
        if (uri == null) throw new IllegalStateException("无法创建封面文件");
        output = activity.getContentResolver().openOutputStream(uri, "w");
      } else {
        File directory = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS), DEFAULT_FOLDER
                + (authorFolder == null ? "" : "/" + authorFolder));
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建作者目录");
        file = new File(directory, fileName + ".jpg");
        output = new FileOutputStream(file);
      }
      if (output == null) throw new IllegalStateException("无法写入封面");
      copyRaw(cover.getInputStream(), output);
      if (Build.VERSION.SDK_INT >= 29 && uri != null) {
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        activity.getContentResolver().update(uri, done, null, null);
      }
    } catch (Exception error) {
      if (uri != null) try { activity.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
      if (file != null && file.exists()) file.delete();
      throw error;
    } finally {
      if (cover != null) cover.disconnect();
    }
  }

  private Uri resolveTreeParent(Uri treeUri, String authorFolder) throws Exception {
    Uri root = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    if (authorFolder == null) return root;
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root,
        DocumentsContract.getDocumentId(root));
    try (Cursor cursor = activity.getContentResolver().query(children,
        new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
      if (cursor != null) while (cursor.moveToNext()) {
        if (authorFolder.equals(cursor.getString(1))) {
          return DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0));
        }
      }
    }
    Uri created = DocumentsContract.createDocument(activity.getContentResolver(), root,
        DocumentsContract.Document.MIME_TYPE_DIR, authorFolder);
    if (created == null) throw new IllegalStateException("无法创建作者文件夹");
    return created;
  }

  private void downloadCoverToTree(VideoItem item, Uri parent, String fileName) throws Exception {
    Uri coverUri = DocumentsContract.createDocument(activity.getContentResolver(), parent,
        "image/jpeg", fileName + ".jpg");
    if (coverUri == null) throw new IllegalStateException("无法创建封面文件");
    HttpURLConnection cover = null;
    try {
      cover = openMediaConnection(item, item.coverUrl);
      int response = cover.getResponseCode();
      if (response < 200 || response >= 300) throw new IllegalStateException("封面 HTTP " + response);
      OutputStream output = activity.getContentResolver().openOutputStream(coverUri, "w");
      if (output == null) throw new IllegalStateException("无法写入封面");
      copyRaw(cover.getInputStream(), output);
    } catch (Exception error) {
      try { DocumentsContract.deleteDocument(activity.getContentResolver(), coverUri); } catch (Exception ignored) {}
      throw error;
    } finally {
      if (cover != null) cover.disconnect();
    }
  }

  private static void copyRaw(InputStream input, OutputStream output) throws Exception {
    try (InputStream in = input; OutputStream out = output) {
      byte[] buffer = new byte[32 * 1024];
      int count;
      while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
      out.flush();
    }
  }

  private static boolean isHttp(String value) {
    return value != null && (value.startsWith("https://") || value.startsWith("http://"));
  }

  private void copyResponse(VideoItem item, HttpURLConnection connection, OutputStream output, long total)
      throws Exception {
    try (InputStream input = connection.getInputStream(); OutputStream out = output) {
      byte[] buffer = new byte[64 * 1024];
      long downloaded = 0;
      long lastUpdate = 0;
      int count;
      while ((count = input.read(buffer)) >= 0) {
        out.write(buffer, 0, count);
        downloaded += count;
        long now = System.currentTimeMillis();
        if (now - lastUpdate >= 500) {
          lastUpdate = now;
          final long done = downloaded;
          final int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
          activity.runOnUiThread(new Runnable() {
            @Override public void run() {
              showProgress(item, percent, percent >= 0
                  ? "正在下载 " + percent + "% · " + formatBytes(done) + " / " + formatBytes(total)
                  : "正在下载 · " + formatBytes(done));
            }
          });
        }
      }
      out.flush();
    }
  }

  private void addHeaders(DownloadManager.Request request, VideoItem item) {
    String cookies = CookieStore.cookieHeader(activity, item.platform, item.mediaUrl);
    if (cookies != null) request.addRequestHeader("Cookie", cookies);
    request.addRequestHeader("Referer", item.resolvedUrl);
    request.addRequestHeader("User-Agent", WebSettings.getDefaultUserAgent(activity));
  }

  private void registerReceiver() {
    receiver = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        String path = pendingDownloads.remove(id);
        if (path == null) return;
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
          if (cursor != null && cursor.moveToFirst()) {
            int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (state == DownloadManager.STATUS_SUCCESSFUL) {
              showStatus("下载完成 · " + path);
              Toast.makeText(activity, "下载完成：" + path, Toast.LENGTH_LONG).show();
            } else {
              int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
              DiagnosticLog.e(activity, LOG_TAG,
                  "system download failed reason=" + reason);
              showStatus("下载失败 · 错误码 " + reason);
              Toast.makeText(activity, "下载失败，错误码：" + reason, Toast.LENGTH_LONG).show();
            }
          }
        }
      }
    };
    IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
    // Android 14+ 强制:targetSdk>=34 应用必须显式声明 RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED。
    // 该 receiver 仅监听自身 DownloadManager 完成事件,属于内部消费,应使用 NOT_EXPORTED。
    if (Build.VERSION.SDK_INT >= 33) {
      activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    } else {
      activity.registerReceiver(receiver, filter);
    }
  }

  private SharedPreferences preferences() {
    return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private void showProgress(VideoItem item, int percent, String text) {
    final ProgressBar bar = progressBars.get(item);
    activity.runOnUiThread(new Runnable() {
      @Override public void run() {
        if (bar != null) {
          bar.setVisibility(android.view.View.VISIBLE);
          if (percent < 0) bar.setIndeterminate(true);
          else {
            bar.setIndeterminate(false);
            bar.setMax(100);
            bar.setProgress(Math.max(0, Math.min(100, percent)));
          }
          Object tag = bar.getTag();
          if (tag instanceof TextView) {
            TextView label = (TextView) tag;
            label.setVisibility(android.view.View.VISIBLE);
            label.setText(text);
          }
        }
        showStatus(text);
      }
    });
  }

  private void notifyFailure(final VideoItem item, final Throwable error) {
    DiagnosticLog.e(activity, LOG_TAG, "download failed", error);
    activity.runOnUiThread(new Runnable() {
      @Override public void run() {
        ProgressBar bar = progressBars.remove(item);
        String detail = error.getMessage() == null
            ? error.getClass().getSimpleName() : error.getMessage();
        if (bar != null) {
          bar.setVisibility(android.view.View.GONE);
          Object tag = bar.getTag();
          if (tag instanceof TextView) {
            TextView label = (TextView) tag;
            label.setVisibility(android.view.View.VISIBLE);
            label.setTextColor(0xFFC62828);
            label.setText("下载失败:" + detail);
          }
        }
        showStatus("下载失败");
        Toast.makeText(activity, "下载失败:" + detail, Toast.LENGTH_LONG).show();
      }
    });
  }

  private static String upgradeToHttps(String value) {
    return value != null && value.startsWith("http://")
        ? "https://" + value.substring(7) : value;
  }

  private void showStatus(String value) {
    if (status != null) status.setText(value);
  }

  private static String formatBytes(long value) {
    if (value < 0) return "?";
    if (value < 1024L) return value + " B";
    if (value < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", value / 1024d);
    if (value < 1024L * 1024L * 1024L) {
      return String.format(Locale.US, "%.1f MB", value / (1024d * 1024d));
    }
    return String.format(Locale.US, "%.2f GB", value / (1024d * 1024d * 1024d));
  }

  void close() {
    if (receiver != null) activity.unregisterReceiver(receiver);
    executor.shutdownNow();
  }
}
