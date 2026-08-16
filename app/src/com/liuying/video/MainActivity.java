package com.liuying.video;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
  private static final int REQUEST_DOUYIN_BROWSER = 1001;
  private static final int REQUEST_COOKIE_IMPORT = 1003;
  private static final int REQUEST_LOG_EXPORT = 1004;
  private DownloadController downloadController;
  private final ExecutorService executor = Executors.newFixedThreadPool(3);
  private EditText input;
  private LinearLayout list;
  private TextView empty;
  private TextView status;
  private TextView folderLabel;
  private LinearLayout browserPendingRow;
  private final Map<LinearLayout, CheckBox> itemChecks = new LinkedHashMap<>();
  private final Map<LinearLayout, VideoItem> rowItems = new LinkedHashMap<>();
  private final java.util.Set<String> pendingUrls = new java.util.LinkedHashSet<>();
  private EditText dateFromInput;
  private EditText dateToInput;
  private EditText durationMinInput;
  private final Map<Platform, TextView> cookieStateViews = new LinkedHashMap<>();

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    CookieStore.init(this);
    DiagnosticLog.i(this, "App", "应用启动");
    setContentView(buildUi());
    downloadController = new DownloadController(this);
    downloadController.attachViews(status, folderLabel);
    handleSharedText(getIntent());
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    handleSharedText(intent);
  }

  @Override protected void onResume() {
    super.onResume();
    refreshCookieStates();
  }

  private View buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(246, 247, 249));

    LinearLayout appBar = new LinearLayout(this);
    appBar.setGravity(Gravity.CENTER_VERTICAL);
    appBar.setPadding(dp(18), dp(6), dp(12), dp(6));
    appBar.setBackgroundColor(Color.WHITE);
    TextView title = label("视频下载", 19, Color.rgb(31, 42, 48));
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    appBar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
    root.addView(appBar);

    FrameLayout content = new FrameLayout(this);
    View downloadPage = buildDownloadPage();
    View settingsPage = buildSettingsPage();
    content.addView(downloadPage, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    content.addView(settingsPage, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    settingsPage.setVisibility(View.GONE);
    root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

    LinearLayout navigation = new LinearLayout(this);
    navigation.setGravity(Gravity.CENTER);
    navigation.setPadding(dp(8), dp(3), dp(8), dp(3));
    navigation.setBackground(makeBackground(Color.WHITE, Color.rgb(226, 230, 236), 0));
    Button downloadTab = command("下载");
    Button settingsTab = command("设置");
    downloadTab.setBackgroundColor(Color.TRANSPARENT);
    settingsTab.setBackgroundColor(Color.TRANSPARENT);
    downloadTab.setTextColor(Color.rgb(25, 112, 95));
    settingsTab.setTextColor(Color.rgb(95, 99, 104));
    downloadTab.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        downloadPage.setVisibility(View.VISIBLE);
        settingsPage.setVisibility(View.GONE);
        title.setText("视频下载");
        downloadTab.setTextColor(Color.rgb(25, 112, 95));
        settingsTab.setTextColor(Color.rgb(95, 99, 104));
      }
    });
    settingsTab.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        downloadPage.setVisibility(View.GONE);
        settingsPage.setVisibility(View.VISIBLE);
        title.setText("设置");
        settingsTab.setTextColor(Color.rgb(25, 112, 95));
        downloadTab.setTextColor(Color.rgb(95, 99, 104));
      }
    });
    navigation.addView(downloadTab, new LinearLayout.LayoutParams(0, dp(52), 1));
    navigation.addView(settingsTab, new LinearLayout.LayoutParams(0, dp(52), 1));
    root.addView(navigation);
    return root;
  }

  private View buildDownloadPage() {
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);

    LinearLayout inputBand = new LinearLayout(this);
    inputBand.setOrientation(LinearLayout.VERTICAL);
    inputBand.setPadding(dp(14), dp(12), dp(14), dp(8));
    inputBand.setBackgroundColor(Color.WHITE);
    input = new EditText(this);
    input.setHint("粘贴视频或主页链接");
    input.setMinLines(2);
    input.setMaxLines(4);
    input.setTextSize(15);
    input.setGravity(Gravity.TOP | Gravity.START);
    input.setBackground(makeBackground(Color.WHITE, Color.rgb(217, 222, 231), 8));
    inputBand.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));

    LinearLayout commands = new LinearLayout(this);
    commands.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    Button clear = command("清空");
    clear.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { input.setText(""); }
    });
    Button parse = primaryCommand("开始解析");
    parse.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { parseInput(); }
    });
    commands.addView(clear);
    commands.addView(parse);
    inputBand.addView(commands);
    page.addView(inputBand);

    HorizontalScrollView selectionScroll = new HorizontalScrollView(this);
    LinearLayout selectionBar = new LinearLayout(this);
    selectionBar.setPadding(dp(10), dp(4), dp(10), dp(4));
    Button selectAll = command("全选");
    selectAll.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { setAllChecked(true); }
    });
    selectionBar.addView(selectAll);
    Button selectNone = command("取消全选");
    selectNone.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { setAllChecked(false); }
    });
    selectionBar.addView(selectNone);
    Button invert = command("反选");
    invert.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { invertChecked(); }
    });
    selectionBar.addView(invert);
    Button remove = command("移除已选");
    remove.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { removeChecked(); }
    });
    selectionBar.addView(remove);
    Button clearList = command("清空列表");
    clearList.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { clearResults(); }
    });
    selectionBar.addView(clearList);
    Button retryFailed = command("重试失败");
    retryFailed.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { retryFailedTasks(); }
    });
    selectionBar.addView(retryFailed);
    Button downloadSelected = primaryCommand("下载已选");
    downloadSelected.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { downloadChecked(); }
    });
    selectionBar.addView(downloadSelected);
    selectionScroll.addView(selectionBar);
    page.addView(selectionScroll);

    HorizontalScrollView filterScroll = new HorizontalScrollView(this);
    LinearLayout filterBar = new LinearLayout(this);
    filterBar.setPadding(dp(10), dp(4), dp(10), dp(4));
    dateFromInput = new EditText(this);
    dateFromInput.setHint("起始日期 yyyy-MM-dd");
    filterBar.addView(dateFromInput, new LinearLayout.LayoutParams(dp(150), dp(44)));
    dateToInput = new EditText(this);
    dateToInput.setHint("结束日期 yyyy-MM-dd");
    filterBar.addView(dateToInput, new LinearLayout.LayoutParams(dp(150), dp(44)));
    Button applyTimeFilter = command("时间过滤");
    applyTimeFilter.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { applyTimeFilterRows(); }
    });
    filterBar.addView(applyTimeFilter);
    Button clearTimeFilter = command("清除时间");
    clearTimeFilter.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { clearTimeFilterRows(); }
    });
    filterBar.addView(clearTimeFilter);
    durationMinInput = new EditText(this);
    durationMinInput.setHint("最短秒数");
    durationMinInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    filterBar.addView(durationMinInput, new LinearLayout.LayoutParams(dp(100), dp(44)));
    Button applyDurationFilter = command("时长过滤");
    applyDurationFilter.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { applyDurationFilterRows(); }
    });
    filterBar.addView(applyDurationFilter);
    Button clearDurationFilter = command("清除时长");
    clearDurationFilter.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { clearDurationFilterRows(); }
    });
    filterBar.addView(clearDurationFilter);
    filterScroll.addView(filterBar);
    page.addView(filterScroll);

    status = label("等待链接", 13, Color.rgb(95, 99, 104));
    status.setPadding(dp(16), dp(9), dp(16), dp(9));
    page.addView(status);
    ScrollView scroll = new ScrollView(this);
    list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(12), 0, dp(12), dp(16));
    empty = label("暂无下载任务", 15, Color.rgb(95, 99, 104));
    empty.setGravity(Gravity.CENTER);
    list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));
    scroll.addView(list);
    page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    return page;
  }

  private View buildSettingsPage() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(16), dp(16), dp(16), dp(20));

    TextView storageTitle = label("存储", 16, Color.rgb(32, 33, 36));
    storageTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    page.addView(storageTitle);
    folderLabel = label("保存位置：Download/视频下载/", 14, Color.rgb(95, 99, 104));
    page.addView(folderLabel);
    Button folder = command("更改保存位置");
    folder.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { downloadController.chooseFolder(); }
    });
    page.addView(folder);
    Button history = command("系统下载记录");
    history.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
    });
    page.addView(history);

    TextView namingTitle = label("文件命名", 16, Color.rgb(32, 33, 36));
    namingTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    namingTitle.setPadding(0, dp(18), 0, dp(6));
    page.addView(namingTitle);
    final android.content.SharedPreferences namingPreferences =
        getSharedPreferences(FileNamer.PREFS, Context.MODE_PRIVATE);
    page.addView(label("原软件格式:1.标题、2.标题;下载时按作者创建文件夹。",
        13, Color.rgb(95, 99, 104)));
    final CheckBox removeHashtags = new CheckBox(this);
    removeHashtags.setText("文件名去除话题标签");
    removeHashtags.setChecked(namingPreferences.getBoolean(FileNamer.KEY_REMOVE_HASHTAGS, true));
    page.addView(removeHashtags);
    final CheckBox addSequence = new CheckBox(this);
    addSequence.setText("添加序号(1.、2.、3.)");
    addSequence.setChecked(namingPreferences.getBoolean(FileNamer.KEY_ADD_SEQUENCE, true));
    page.addView(addSequence);
    final CheckBox authorFolder = new CheckBox(this);
    authorFolder.setText("按视频作者创建文件夹");
    authorFolder.setChecked(namingPreferences.getBoolean(FileNamer.KEY_AUTHOR_FOLDER, true));
    page.addView(authorFolder);
    final CheckBox downloadCover = new CheckBox(this);
    downloadCover.setText("同时下载封面(同名 .jpg)");
    downloadCover.setChecked(namingPreferences.getBoolean(FileNamer.KEY_DOWNLOAD_COVER, true));
    page.addView(downloadCover);
    final EditText maxLengthInput = new EditText(this);
    maxLengthInput.setSingleLine(true);
    maxLengthInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    maxLengthInput.setHint("标题最大长度(20-200)");
    maxLengthInput.setText(String.valueOf(namingPreferences.getInt(FileNamer.KEY_MAX_LENGTH, 100)));
    page.addView(maxLengthInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    Button saveNaming = command("保存下载规则");
    saveNaming.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        int maxLength = 100;
        try { maxLength = Integer.parseInt(maxLengthInput.getText().toString().trim()); }
        catch (Exception ignored) {}
        maxLength = Math.max(20, Math.min(200, maxLength));
        maxLengthInput.setText(String.valueOf(maxLength));
        namingPreferences.edit()
            .putBoolean(FileNamer.KEY_REMOVE_HASHTAGS, removeHashtags.isChecked())
            .putBoolean(FileNamer.KEY_ADD_SEQUENCE, addSequence.isChecked())
            .putBoolean(FileNamer.KEY_AUTHOR_FOLDER, authorFolder.isChecked())
            .putBoolean(FileNamer.KEY_DOWNLOAD_COVER, downloadCover.isChecked())
            .putInt(FileNamer.KEY_MAX_LENGTH, maxLength)
            .apply();
        Toast.makeText(MainActivity.this, "下载规则已保存", Toast.LENGTH_SHORT).show();
      }
    });
    page.addView(saveNaming);

    TextView downloadSettingsTitle = label("下载设置", 16, Color.rgb(32, 33, 36));
    downloadSettingsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    downloadSettingsTitle.setPadding(0, dp(18), 0, dp(6));
    page.addView(downloadSettingsTitle);
    final android.content.SharedPreferences downloadPreferences =
        getSharedPreferences("download_preferences", Context.MODE_PRIVATE);
    final EditText workersInput = new EditText(this);
    workersInput.setSingleLine(true);
    workersInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    workersInput.setHint("下载线程数 1-6");
    workersInput.setText(String.valueOf(downloadPreferences.getInt("download_workers", 2)));
    page.addView(workersInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    Button saveWorkers = command("保存");
    saveWorkers.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        int workers = 2;
        try { workers = Integer.parseInt(workersInput.getText().toString().trim()); }
        catch (Exception ignored) {}
        workers = Math.max(1, Math.min(6, workers));
        workersInput.setText(String.valueOf(workers));
        downloadPreferences.edit().putInt("download_workers", workers).apply();
        Toast.makeText(MainActivity.this, "下载线程数已保存为 " + workers, Toast.LENGTH_SHORT).show();
      }
    });
    page.addView(saveWorkers);

    TextView accountTitle = label("平台账号", 16, Color.rgb(32, 33, 36));
    accountTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    accountTitle.setPadding(0, dp(18), 0, dp(6));
    page.addView(accountTitle);
    Button importCookies = command("导入 Cookie 文件");
    importCookies.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_COOKIE_IMPORT);
      }
    });
    page.addView(importCookies);
    Button clearCookies = command("清除所有平台 Cookie");
    clearCookies.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        CookieStore.clearAll(MainActivity.this);
        refreshCookieStates();
        Toast.makeText(MainActivity.this, "已清除所有平台 Cookie", Toast.LENGTH_SHORT).show();
      }
    });
    page.addView(clearCookies);
    for (Platform platform : new Platform[] {Platform.DOUYIN, Platform.KUAISHOU,
        Platform.XIAOHONGSHU, Platform.BILIBILI, Platform.YOUTUBE}) {
      final Platform selectedPlatform = platform;
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      Button button = command(selectedPlatform.displayName + " 登录");
      button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      button.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) { openLogin(selectedPlatform); }
      });
      row.addView(button, new LinearLayout.LayoutParams(0, dp(50), 1));
      TextView state = label("未登录", 12, Color.rgb(95, 99, 104));
      state.setGravity(Gravity.CENTER_VERTICAL);
      state.setPadding(dp(10), 0, dp(6), 0);
      row.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(50)));
      cookieStateViews.put(selectedPlatform, state);
      page.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
    }

    TextView logTitle = label("诊断日志", 16, Color.rgb(32, 33, 36));
    logTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    logTitle.setPadding(0, dp(18), 0, dp(6));
    page.addView(logTitle);
    Button viewLog = command("查看日志");
    viewLog.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { showDiagnosticLog(); }
    });
    page.addView(viewLog);
    Button exportLog = command("导出日志");
    exportLog.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { exportDiagnosticLog(); }
    });
    page.addView(exportLog);
    Button clearLog = command("清空日志");
    clearLog.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { confirmClearDiagnosticLog(); }
    });
    page.addView(clearLog);
    scroll.addView(page);
    return scroll;
  }

  private void parseInput() {
    List<String> urls = Resolver.extractSupportedUrls(input.getText().toString());
    DiagnosticLog.i(this, "Parse", "提交解析,识别链接数=" + urls.size());
    if (urls.isEmpty()) {
      Toast.makeText(this, "未找到受支持的平台链接", Toast.LENGTH_SHORT).show();
      return;
    }
    if (empty.getParent() != null) list.removeView(empty);
    int added = 0;
    for (String url : urls) {
      if (pendingUrls.add(url)) {
        addPending(url);
        added++;
      }
    }
    status.setText(added > 0 ? "正在解析 " + added + " 个链接" : "链接已在任务列表中");
  }

  private void addPending(String url) {
    final String targetUrl = url;
    DiagnosticLog.i(this, "Parse", "开始解析 platform=" + Platform.detect(targetUrl)
        + " url=" + targetUrl);
    final LinearLayout row = taskContainer();
    TextView title = label(Platform.detect(targetUrl).displayName + " · 解析中", 16, Color.rgb(32, 33, 36));
    TextView detail = label(shortUrl(targetUrl), 13, Color.rgb(95, 99, 104));
    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    row.addView(title);
    row.addView(detail);
    row.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
    list.addView(row);
    executor.submit(new Runnable() {
      @Override public void run() {
        try {
          CookieStore.restore(MainActivity.this, Platform.detect(targetUrl));
          final List<VideoItem> items = Resolver.resolveAll(targetUrl);
          DiagnosticLog.i(MainActivity.this, "Parse", "解析成功 platform="
              + Platform.detect(targetUrl) + " count=" + items.size());
          runOnUiThread(new Runnable() {
            @Override public void run() { renderResults(row, items); }
          });
        } catch (final Exception error) {
          DiagnosticLog.e(MainActivity.this, "Parse", "解析失败 platform="
              + Platform.detect(targetUrl) + " url=" + targetUrl, error);
          runOnUiThread(new Runnable() {
            @Override public void run() {
              if (Platform.detect(targetUrl) == Platform.DOUYIN) {
                row.removeAllViews();
                TextView deepTitle = label("抖音 · 深度解析", 16, Color.rgb(31, 42, 48));
                deepTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                row.addView(deepTitle);
                row.addView(label("正在自动获取作品数据", 13, Color.rgb(91, 103, 112)));
                status.setText("正在进行深度解析");
                openDouyinBrowser(row, targetUrl);
              } else renderError(row, targetUrl, error);
            }
          });
        }
      }
    });
  }

  private void renderResults(LinearLayout pendingRow, List<VideoItem> items) {
    if (items.isEmpty()) {
      renderError(pendingRow, "", new IllegalStateException("没有解析到可下载作品"));
      return;
    }
    renderResolved(pendingRow, items.get(0));
    int insertAt = list.indexOfChild(pendingRow) + 1;
    for (int i = 1; i < items.size(); i++) {
      LinearLayout row = taskContainer();
      list.addView(row, insertAt++);
      renderResolved(row, items.get(i));
    }
    status.setText("已解析 " + items.size() + " 个可下载视频");
    updateSelectedCount();
  }

  private void renderResolved(LinearLayout row, VideoItem item) {
    row.removeAllViews();
    itemChecks.remove(row);
    rowItems.remove(row);
    CheckBox check = new CheckBox(this);
    check.setText("选择");
    check.setChecked(true);
    check.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
      @Override public void onCheckedChanged(android.widget.CompoundButton button, boolean isChecked) {
        updateSelectedCount();
      }
    });
    row.addView(check);
    itemChecks.put(row, check);
    rowItems.put(row, item);
    TextView title = label(displayTitle(item), 16, Color.rgb(32, 33, 36));
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    row.addView(title);
    row.addView(label(metadataLine(item), 13, Color.rgb(95, 99, 104)));
    row.addView(label(item.platform.displayName + " · " + item.message, 12, Color.rgb(112, 117, 122)));
    final ProgressBar downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    downloadProgress.setMax(100);
    downloadProgress.setProgress(0);
    downloadProgress.setVisibility(View.GONE);
    final TextView progressText = label("", 12, Color.rgb(25, 118, 210));
    progressText.setVisibility(View.GONE);
    downloadProgress.setTag(progressText);
    row.addView(downloadProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
    row.addView(progressText);
    LinearLayout actions = new LinearLayout(this);
    actions.setGravity(Gravity.END);
    Button open = command("预览");
    open.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { openUrl(item.mediaUrl); }
    });
    actions.addView(open);
    final Button download = primaryCommand("下载");
    download.setEnabled(item.canDownload());
    download.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        downloadProgress.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        progressText.setText("准备下载...");
        download.setText("下载中");
        download.setEnabled(false);
        download.setTag(downloadProgress);
        downloadController.enqueue(item, sequenceOf(item), downloadProgress);
        restoreButtonWhenDone(download, downloadProgress);
      }
    });
    actions.addView(download);
    row.addView(actions);
    status.setText("已获取可下载视频");
  }

  private void renderError(final LinearLayout row, final String url, Exception error) {
    DiagnosticLog.e(this, "ParseUI", "显示解析失败 url=" + url, error);
    itemChecks.remove(row);
    rowItems.remove(row);
    row.removeAllViews();
    row.setTag(url);
    row.addView(label(Platform.detect(url).displayName + " · 解析失败", 16, Color.rgb(183, 28, 28)));
    row.addView(label(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
        13, Color.rgb(95, 99, 104)));
    final boolean douyin = Platform.detect(url) == Platform.DOUYIN;
    Button open = command(douyin ? "重新解析" : "在浏览器打开");
    open.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        if (douyin) openDouyinBrowser(row, url); else openUrl(url);
      }
    });
    row.addView(open);
    status.setText("部分任务解析失败");
  }


  private void openDouyinBrowser(LinearLayout row, String url) {
    browserPendingRow = row;
    Intent intent = new Intent(this, DouyinBrowserActivity.class);
    intent.putExtra(DouyinBrowserActivity.EXTRA_URL, url);
    startActivityForResult(intent, REQUEST_DOUYIN_BROWSER);
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_LOG_EXPORT) {
      if (resultCode == RESULT_OK && data != null && data.getData() != null) {
        try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "w")) {
          if (output == null) throw new IllegalStateException("无法创建日志文件");
          output.write(DiagnosticLog.read(this).getBytes(StandardCharsets.UTF_8));
          output.flush();
          DiagnosticLog.i(this, "Log", "诊断日志导出成功");
          Toast.makeText(this, "诊断日志已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
          DiagnosticLog.e(this, "Log", "诊断日志导出失败", error);
          Toast.makeText(this, "导出失败:" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
      }
      return;
    }
    if (downloadController.handleActivityResult(requestCode, resultCode, data)) return;
    if (requestCode == REQUEST_COOKIE_IMPORT) {
      if (resultCode == RESULT_OK && data != null && data.getData() != null) {
        android.net.Uri uri = data.getData();
        try {
          java.io.InputStream input = getContentResolver().openInputStream(uri);
          java.io.File temp = new File(getCacheDir(), "cookie_import.json");
          java.io.FileOutputStream output = new java.io.FileOutputStream(temp);
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
          output.close();
          input.close();
          boolean ok = CookieStore.importFromJson(this, temp.getAbsolutePath());
          temp.delete();
          if (ok) {
            DiagnosticLog.i(this, "Cookie", "Cookie 文件导入成功");
            refreshCookieStates();
          } else {
            DiagnosticLog.w(this, "Cookie", "Cookie 文件导入失败:格式或平台无效");
          }
          Toast.makeText(this, ok ? "Cookie 导入成功" : "Cookie 导入失败,请检查文件格式",
              Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
          DiagnosticLog.e(this, "Cookie", "读取 Cookie 文件失败", error);
          Toast.makeText(this, "读取文件失败:" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
      }
      return;
    }
    if (requestCode != REQUEST_DOUYIN_BROWSER || resultCode != RESULT_OK || data == null) return;
    try {
      JSONArray array = new JSONArray(data.getStringExtra(DouyinBrowserActivity.EXTRA_RESULT));
      java.util.ArrayList<VideoItem> items = new java.util.ArrayList<>();
      for (int i = 0; i < array.length(); i++) {
        JSONObject value = array.getJSONObject(i);
        String pageUrl = value.optString("pageUrl");
        String mediaUrl = value.optString("mediaUrl");
        String title = value.optString("title", "douyin_video_" + (i + 1));
        title = title.replaceAll("[\\/:*?\"<>|\r\n\t]", " ").trim();
        String coverUrl = value.optString("coverUrl", null);
        String authorName = value.optString("authorName", null);
        String videoId = value.optString("videoId", null);
        JSONArray imageArray = value.optJSONArray("imageUrls");
        List<String> imageUrls = new ArrayList<>();
        if (imageArray != null) {
          for (int j = 0; j < imageArray.length(); j++) {
            String imageUrl = imageArray.optString(j, "");
            if (imageUrl.startsWith("https://") || imageUrl.startsWith("http://")) {
              imageUrls.add(imageUrl);
            }
          }
        }
        boolean imagePost = !imageUrls.isEmpty();
        if (!imagePost && !mediaUrl.startsWith("https://") && !mediaUrl.startsWith("http://")) continue;
        items.add(new VideoItem(Platform.DOUYIN, pageUrl, pageUrl, title,
            imagePost ? null : mediaUrl, imagePost ? imageUrls.get(0) : coverUrl,
            imagePost ? "浏览器会话已获取图文图片" : "浏览器会话已获取直链", authorName,
            0, 0, imagePost ? "image" : "video", null, videoId,
            imagePost ? imageUrls : null));
      }
      if (items.isEmpty()) throw new IllegalStateException("浏览器没有捕获到可下载视频");
      LinearLayout row = browserPendingRow != null ? browserPendingRow : taskContainer();
      if (browserPendingRow == null) list.addView(row);
      renderResults(row, items);
    } catch (Exception error) {
      Toast.makeText(this, "浏览器解析结果无效: " + error.getMessage(), Toast.LENGTH_LONG).show();
    } finally {
      browserPendingRow = null;
    }
  }

  private void showDiagnosticLog() {
    String content = DiagnosticLog.read(this);
    if (content.trim().isEmpty()) content = "暂无诊断日志";
    TextView text = new TextView(this);
    text.setText(content);
    text.setTextSize(12);
    text.setTextColor(Color.rgb(32, 33, 36));
    text.setTextIsSelectable(true);
    text.setPadding(dp(12), dp(8), dp(12), dp(8));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(text);
    new AlertDialog.Builder(this)
        .setTitle("诊断日志")
        .setView(scroll)
        .setPositiveButton("关闭", null)
        .show();
  }

  private void exportDiagnosticLog() {
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TITLE, "liuying-diagnostic.log");
    startActivityForResult(intent, REQUEST_LOG_EXPORT);
  }

  private void confirmClearDiagnosticLog() {
    new AlertDialog.Builder(this)
        .setTitle("清空诊断日志")
        .setMessage("确认删除当前和上一次诊断日志?")
        .setNegativeButton("取消", null)
        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
          @Override public void onClick(DialogInterface dialog, int which) {
            DiagnosticLog.clear(MainActivity.this);
            DiagnosticLog.i(MainActivity.this, "Log", "诊断日志已清空");
            Toast.makeText(MainActivity.this, "诊断日志已清空", Toast.LENGTH_SHORT).show();
          }
        })
        .show();
  }

  private void refreshCookieStates() {
    for (Map.Entry<Platform, TextView> entry : cookieStateViews.entrySet()) {
      TextView view = entry.getValue();
      if (view == null) continue;
      String text = CookieStore.statusText(this, entry.getKey());
      view.setText(text);
      view.setTextColor("已登录".equals(text)
          ? Color.rgb(25, 112, 95) : Color.rgb(95, 99, 104));
    }
  }

  private void openLogin(Platform platform) {
    if (platform == Platform.YOUTUBE) {
      try {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"));
        startActivity(browserIntent);
        DiagnosticLog.i(this, "Login", "已打开 YouTube 系统浏览器登录");
        Toast.makeText(this,
            "Google 不支持应用内 WebView 登录;系统浏览器 Cookie 不会自动同步,受限视频请使用导入 Cookie 文件",
            Toast.LENGTH_LONG).show();
      } catch (Exception error) {
        DiagnosticLog.e(this, "Login", "打开 YouTube 系统浏览器失败", error);
        Toast.makeText(this, "无法打开系统浏览器,请确认已安装可用浏览器", Toast.LENGTH_LONG).show();
      }
      return;
    }
    Intent intent = new Intent(this, LoginActivity.class);
    intent.putExtra(LoginActivity.EXTRA_PLATFORM, platform.name());
    startActivity(intent);
  }

  private void openUrl(String url) {
    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
  }

  private void handleSharedText(Intent intent) {
    if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
      String text = intent.getStringExtra(Intent.EXTRA_TEXT);
      if (text != null) input.setText(text);
    }
  }

  private LinearLayout taskContainer() {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(14), dp(12), dp(14), dp(10));
    row.setBackground(makeBackground(Color.WHITE, Color.rgb(226, 230, 236), 8));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.setMargins(0, 0, 0, dp(8));
    row.setLayoutParams(params);
    return row;
  }

  private Button command(String text) {
    Button button = new Button(this);
    button.setText(text);
    button.setTextSize(14);
    button.setMinHeight(dp(44));
    button.setMinWidth(0);
    button.setPadding(dp(14), 0, dp(14), 0);
    button.setAllCaps(false);
    button.setTextColor(Color.rgb(48, 58, 64));
    button.setBackground(makeBackground(Color.rgb(243, 245, 247), Color.rgb(217, 222, 231), 8));
    return button;
  }

  private Button primaryCommand(String text) {
    Button button = command(text);
    button.setTextColor(Color.WHITE);
    button.setTypeface(null, android.graphics.Typeface.BOLD);
    button.setBackground(makeBackground(Color.rgb(25, 112, 95), 0, 8));
    return button;
  }

  private GradientDrawable makeBackground(int color, int strokeColor, int radiusDp) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setShape(GradientDrawable.RECTANGLE);
    drawable.setColor(color);
    drawable.setCornerRadius(dp(radiusDp));
    if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
    return drawable;
  }

  private TextView iconButton(String text, String description) {
    TextView view = label(text, 25, Color.WHITE);
    view.setGravity(Gravity.CENTER);
    view.setContentDescription(description);
    view.setBackgroundResource(android.R.drawable.list_selector_background);
    return view;
  }

  private TextView label(String text, int size, int color) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setPadding(0, dp(4), 0, dp(4));
    return view;
  }

  private void updateSelectedCount() {
    int selected = 0;
    for (CheckBox check : itemChecks.values()) if (check.isChecked()) selected++;
    status.setText(selected > 0 ? "已选 " + selected + " / " + itemChecks.size() + " 项" : "无选中项");
  }

  private void setAllChecked(boolean checked) {
    for (CheckBox check : itemChecks.values()) check.setChecked(checked);
    updateSelectedCount();
  }

  private void invertChecked() {
    for (CheckBox check : itemChecks.values()) check.setChecked(!check.isChecked());
    updateSelectedCount();
  }

  private void removeChecked() {
    List<LinearLayout> removed = new ArrayList<>();
    for (Map.Entry<LinearLayout, CheckBox> entry : itemChecks.entrySet()) {
      if (entry.getValue().isChecked()) removed.add(entry.getKey());
    }
    for (LinearLayout row : removed) {
      list.removeView(row);
      itemChecks.remove(row);
      rowItems.remove(row);
    }
    status.setText("已移除 " + removed.size() + " 项");
    showEmptyIfNeeded();
  }

  private void clearResults() {
    list.removeAllViews();
    itemChecks.clear();
    rowItems.clear();
    pendingUrls.clear();
    list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));
    status.setText("已清空解析结果");
  }

  private void downloadChecked() {
    int count = 0;
    int sequence = 0;
    for (Map.Entry<LinearLayout, CheckBox> entry : itemChecks.entrySet()) {
      VideoItem item = rowItems.get(entry.getKey());
      if (entry.getValue().isChecked() && item != null && item.canDownload()) {
        sequence++;
        ProgressBar bar = findProgressBar(entry.getKey());
        if (bar != null) {
          bar.setVisibility(View.VISIBLE);
          Object tag = bar.getTag();
          if (tag instanceof TextView) {
            TextView text = (TextView) tag;
            text.setVisibility(View.VISIBLE);
            text.setText("准备下载...");
          }
        }
        downloadController.enqueue(item, sequence, bar);
        count++;
      }
    }
    if (count == 0) Toast.makeText(this, "没有选中可下载的视频", Toast.LENGTH_SHORT).show();
    else status.setText("已提交 " + count + " 个下载任务");
  }

  private ProgressBar findProgressBar(LinearLayout row) {
    for (int i = 0; i < row.getChildCount(); i++) {
      if (row.getChildAt(i) instanceof ProgressBar) return (ProgressBar) row.getChildAt(i);
    }
    return null;
  }

  private int sequenceOf(VideoItem target) {
    int sequence = 0;
    for (VideoItem item : rowItems.values()) {
      sequence++;
      if (item == target) return sequence;
    }
    return 1;
  }

  private void showEmptyIfNeeded() {
    if (!itemChecks.isEmpty() || list.getChildCount() > 0) return;
    list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)));
  }

  private String displayTitle(VideoItem item) {
    String value = item.title == null ? "未命名视频" : item.title;
    value = value.replaceAll("#([^#\\s,。!?、,;;::]+)", "").replaceAll("\\s+", " ").trim();
    return value.isEmpty() ? "未命名视频" : value;
  }

  private String metadataLine(VideoItem item) {
    StringBuilder value = new StringBuilder();
    value.append(item.authorName == null || item.authorName.trim().isEmpty() ? "未知作者" : item.authorName);
    if (item.durationSeconds > 0) {
      long minutes = item.durationSeconds / 60;
      long seconds = item.durationSeconds % 60;
      value.append(" · ").append(String.format(Locale.CHINA, "%02d:%02d", minutes, seconds));
    }
    if (item.publishTime > 0) {
      value.append(" · ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
          .format(new Date(item.publishTime * 1000L)));
    }
    value.append(" · ").append("image".equals(item.contentType) ? "图文" : "视频");
    return value.toString();
  }

  private String shortUrl(String url) {
    return url.length() > 90 ? url.substring(0, 87) + "..." : url;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void applyTimeFilterRows() {
    String fromStr = dateFromInput.getText().toString().trim();
    String toStr = dateToInput.getText().toString().trim();
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    long fromTime = 0;
    long toTime = Long.MAX_VALUE;
    try {
      if (!fromStr.isEmpty()) fromTime = dateFormat.parse(fromStr).getTime() / 1000;
      if (!toStr.isEmpty()) toTime = dateFormat.parse(toStr).getTime() / 1000 + 86400;
    } catch (Exception e) {
      Toast.makeText(this, "日期格式错误", Toast.LENGTH_SHORT).show();
      return;
    }
    for (Map.Entry<LinearLayout, VideoItem> entry : rowItems.entrySet()) {
      VideoItem item = entry.getValue();
      if (item.publishTime < fromTime || item.publishTime > toTime) {
        entry.getKey().setVisibility(View.GONE);
        CheckBox check = itemChecks.get(entry.getKey());
        if (check != null) check.setChecked(false);
      }
    }
    updateVisibleCount();
  }

  private void clearTimeFilterRows() {
    for (LinearLayout row : rowItems.keySet()) {
      row.setVisibility(View.VISIBLE);
    }
    updateVisibleCount();
  }

  private void applyDurationFilterRows() {
    int minSeconds = 0;
    try {
      minSeconds = Integer.parseInt(durationMinInput.getText().toString().trim());
    } catch (Exception e) {
      Toast.makeText(this, "请输入有效秒数", Toast.LENGTH_SHORT).show();
      return;
    }
    for (Map.Entry<LinearLayout, VideoItem> entry : rowItems.entrySet()) {
      if (entry.getValue().durationSeconds < minSeconds) {
        entry.getKey().setVisibility(View.GONE);
      }
    }
    updateVisibleCount();
  }

  private void clearDurationFilterRows() {
    for (LinearLayout row : rowItems.keySet()) {
      row.setVisibility(View.VISIBLE);
    }
    updateVisibleCount();
  }

  private void updateVisibleCount() {
    int visible = 0;
    for (LinearLayout row : rowItems.keySet()) {
      if (row.getVisibility() == View.VISIBLE) visible++;
    }
    status.setText("可见 " + visible + " / " + rowItems.size() + " 项");
  }

  private void retryFailedTasks() {
    List<LinearLayout> failedRows = new ArrayList<>();
    for (int i = 0; i < list.getChildCount(); i++) {
      View child = list.getChildAt(i);
      if (!(child instanceof LinearLayout)) continue;
      LinearLayout row = (LinearLayout) child;
      if (containsText(row, "解析失败") && row.getTag() instanceof String) {
        failedRows.add(row);
      }
    }
    int retried = 0;
    for (LinearLayout row : failedRows) {
      String url = (String) row.getTag();
      list.removeView(row);
      itemChecks.remove(row);
      rowItems.remove(row);
      addPending(url);
      retried++;
    }
    status.setText(retried > 0 ? "正在重试 " + retried + " 个失败任务" : "没有失败任务");
  }

  private boolean containsText(ViewGroup group, String text) {
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child instanceof TextView) {
        String value = ((TextView) child).getText().toString();
        if (value.contains(text)) return true;
      } else if (child instanceof ViewGroup) {
        if (containsText((ViewGroup) child, text)) return true;
      }
    }
    return false;
  }

  private void restoreButtonWhenDone(final Button button, final ProgressBar bar) {
    button.postDelayed(new Runnable() {
      @Override public void run() {
        if (!button.isAttachedToWindow()) return;
        if (bar.getVisibility() != View.VISIBLE || bar.getProgress() >= 100) {
          button.setText("下载");
          button.setEnabled(true);
          return;
        }
        button.postDelayed(this, 500);
      }
    }, 500);
  }

  @Override protected void onDestroy() {
    if (downloadController != null) downloadController.close();
    executor.shutdownNow();
    super.onDestroy();
  }
}
