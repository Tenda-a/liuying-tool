package com.liuying.video;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DouyinBrowserActivity extends Activity {
  static final String EXTRA_URL = "url";
  static final String EXTRA_RESULT = "result";
  private static final int MAX_PROFILE_VIDEOS = 50;
  private final Handler handler = new Handler();
  private final List<String> pendingPages = new ArrayList<>();
  private final JSONArray results = new JSONArray();
  private WebView webView;
  private TextView status;
  private ProgressBar progress;
  private Button extract;
  private boolean batchRunning;
  private final Set<String> profilePages = new LinkedHashSet<>();
  private int profileAttempts;
  private int extractionAttempts;
  private int pageExtractionAttempts;
  private int pageIndex;
  private String expectedVideoId;
  private long startedAt;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    String sourceUrl = getIntent().getStringExtra(EXTRA_URL);
    if (sourceUrl == null || !Platform.DOUYIN.allowsUrl(sourceUrl)) {
      finish();
      return;
    }
    expectedVideoId = extractVideoId(sourceUrl);
    startedAt = System.currentTimeMillis();
    // 先创建 WebView 再 restore: CookieManager 依赖 WebView 初始化,
    // 顺序颠倒会导致导入的 cookie 无法写入 WebView 会话。
    setContentView(buildUi());
    CookieStore.restore(this, Platform.DOUYIN);
    webView.loadUrl(sourceUrl);
  }

  private View buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.WHITE);

    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(8), dp(4), dp(8), dp(4));
    Button close = button("返回");
    close.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { finish(); }
    });
    status = new TextView(this);
    status.setText("正在加载抖音页面");
    status.setTextSize(15);
    status.setTextColor(Color.rgb(32, 33, 36));
    status.setPadding(dp(8), 0, dp(8), 0);
    extract = button("提取");
    extract.setEnabled(false);
    extract.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { extractCurrentPage(true); }
    });
    bar.addView(close);
    bar.addView(status, new LinearLayout.LayoutParams(0, dp(48), 1));
    bar.addView(extract);
    root.addView(bar);

    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

    webView = new WebView(this);
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    webView.setWebChromeClient(new WebChromeClient() {
      @Override public void onProgressChanged(WebView view, int value) {
        progress.setProgress(value);
        progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
      }
    });
    webView.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return !Platform.DOUYIN.allowsUrl(request.getUrl().toString());
      }

      @Override public void onPageFinished(WebView view, String url) {
        CookieManager.getInstance().flush();
        CookieStore.save(DouyinBrowserActivity.this, Platform.DOUYIN);
        pageExtractionAttempts = 0;
        extract.setEnabled(true);
        status.setText(batchRunning
            ? "正在提取 " + (pageIndex + 1) + "/" + pendingPages.size()
            : "页面已加载，正在识别作品");
        handler.removeCallbacksAndMessages(null);
        // 主页场景: 先滚动到底触发懒加载,再等待更久让作品渲染出来,避免"没识别到作品"。
        final boolean isProfile = url != null && url.contains("/user/");
        handler.postDelayed(new Runnable() {
          @Override public void run() {
            if (isProfile) {
              webView.evaluateJavascript("(function(){window.scrollTo(0,document.body.scrollHeight);var s=document.scrollingElement||document.documentElement||document.body;if(s)s.scrollTop=s.scrollHeight;return true;})()", null);
              handler.postDelayed(new Runnable() {
                @Override public void run() { extractCurrentPage(false); }
              }, 2500);
            } else {
              extractCurrentPage(false);
            }
          }
        }, batchRunning ? 2500 : 4000);
      }
    });
    root.addView(webView, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    return root;
  }

  private void extractCurrentPage(final boolean userRequested) {
    if (webView == null) return;
    if (System.currentTimeMillis() - startedAt > 90000) {
      if (results.length() > 0) finishWithResults();
      else {
        batchRunning = false;
        extract.setEnabled(true);
        status.setText("解析超时,请确认已登录后重试");
      }
      return;
    }
    extract.setEnabled(false);
    webView.evaluateJavascript(extractionScript(), new ValueCallback<String>() {
      @Override public void onReceiveValue(String encoded) {
        try {
          String json = encoded == null || "null".equals(encoded)
              ? "{}" : new JSONArray("[" + encoded + "]").getString(0);
          JSONObject value = new JSONObject(json);
          String pageUrl = value.optString("pageUrl", webView.getUrl());
          String mediaUrl = value.optString("mediaUrl", "");
          String matchedId = value.optString("matchedId", "");
          if (expectedVideoId == null || expectedVideoId.length() == 0) expectedVideoId = extractVideoId(pageUrl);
          JSONArray links = value.optJSONArray("videoPages");
          boolean profilePage = pageUrl != null && pageUrl.contains("/user/");
          if (!batchRunning && profilePage) {
            mergeProfilePages(links);
            if (profileAttempts < 8 && profilePages.size() < MAX_PROFILE_VIDEOS) {
              profileAttempts++;
              status.setText("正在加载主页作品 · " + profileAttempts + "/9");
              webView.evaluateJavascript("(function(){window.scrollTo(0,document.body.scrollHeight);var s=document.scrollingElement||document.documentElement||document.body;if(s)s.scrollTop=s.scrollHeight;var els=document.querySelectorAll('div');for(var i=0;i<els.length;i++){if(els[i].scrollHeight>els[i].clientHeight*2)els[i].scrollTop=els[i].scrollHeight;}return true;})()", null);
              handler.postDelayed(new Runnable() {
                @Override public void run() { extractCurrentPage(false); }
              }, 1200);
              return;
            }
            if (!profilePages.isEmpty()) {
              startBatch(new JSONArray(profilePages));
              return;
            }
            extract.setEnabled(true);
            status.setText("主页没有加载出作品，请先在设置中登录抖音");
            return;
          }
          JSONArray imageUrls = value.optJSONArray("imageUrls");
          boolean notePage = pageUrl != null && pageUrl.contains("/note/");
          // 图文(note)页捕到的 mediaUrl 通常是配乐流，不能据此丢弃图片
          if (isDownloadUrl(mediaUrl) && !notePage) imageUrls = null;
          if (imageUrls != null && imageUrls.length() > 0) {
            addImageResult(pageUrl, value.optString("title", "抖音图文"), imageUrls,
                value.optString("authorName", ""));
            if (batchRunning) loadNextBatchPage(); else finishWithResults();
            return;
          }
          if (isDownloadUrl(mediaUrl) && (expectedVideoId == null || expectedVideoId.length() == 0 || expectedVideoId.equals(matchedId))) {
            addResult(pageUrl, value.optString("title", "抖音视频"), mediaUrl,
                value.optString("coverUrl", ""), value.optString("authorName", ""));
            if (batchRunning) loadNextBatchPage(); else finishWithResults();
            return;
          }
          if (batchRunning) {
            if (pageExtractionAttempts < 1) {
              pageExtractionAttempts++;
              status.setText("正在捕获第 " + (pageIndex + 1) + " 个作品视频流 · " + pageExtractionAttempts + "/3");
              webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();}window.scrollTo(0,document.body.scrollHeight);return true;})()", null);
              handler.postDelayed(new Runnable() {
                @Override public void run() { extractCurrentPage(false); }
              }, 1800);
              return;
            }
            loadNextBatchPage();
            return;
          }
          if (extractionAttempts < 2) {
            extractionAttempts++;
            status.setText("正在获取视频流 · " + extractionAttempts + "/3");
            webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){v.muted=true;v.play();}return true;})()", null);
            handler.postDelayed(new Runnable() {
              @Override public void run() { extractCurrentPage(false); }
            }, 1500);
            return;
          }
          extract.setEnabled(true);
          status.setText("自动解析未获取到视频，可点提取重试");
          if (userRequested) Toast.makeText(DouyinBrowserActivity.this, "当前页面没有可下载的视频流", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
          extract.setEnabled(true);
          status.setText("解析失败：" + error.getMessage());
        }
      }
    });
  }

  private void mergeProfilePages(JSONArray links) {
    if (links == null) return;
    for (int i = 0; i < links.length() && profilePages.size() < MAX_PROFILE_VIDEOS; i++) {
      String url = links.optString(i);
      if (Platform.DOUYIN.allowsUrl(url) && url.contains("/video/")) profilePages.add(url);
    }
  }

  private void startBatch(JSONArray links) {
    Set<String> unique = new LinkedHashSet<>();
    for (int i = 0; i < links.length() && unique.size() < MAX_PROFILE_VIDEOS; i++) {
      String url = links.optString(i);
      if (Platform.DOUYIN.allowsUrl(url) && url.contains("/video/")) unique.add(url);
    }
    pendingPages.clear();
    pendingPages.addAll(unique);
    if (pendingPages.isEmpty()) {
      extract.setEnabled(true);
      status.setText("主页中没有找到公开视频");
      return;
    }
    batchRunning = true;
    pageIndex = 0;
    status.setText("发现 " + pendingPages.size() + " 个作品，开始逐条提取");
    expectedVideoId = extractVideoId(pendingPages.get(0));
    webView.loadUrl(pendingPages.get(0));
  }

  private void loadNextBatchPage() {
    pageIndex++;
    if (pageIndex >= pendingPages.size()) {
      if (results.length() > 0) finishWithResults();
      else {
        batchRunning = false;
        extract.setEnabled(true);
        status.setText("主页作品均未捕获到可下载视频");
      }
      return;
    }
    expectedVideoId = extractVideoId(pendingPages.get(pageIndex));
    webView.loadUrl(pendingPages.get(pageIndex));
  }

  private void addResult(String pageUrl, String title, String mediaUrl, String coverUrl, String authorName) throws Exception {
    String normalized = normalizeVideoUrl(mediaUrl);
    for (int i = 0; i < results.length(); i++) {
      if (normalized.equals(results.getJSONObject(i).optString("mediaUrl"))) return;
    }
    JSONObject item = new JSONObject();
    item.put("pageUrl", pageUrl);
    item.put("title", title);
    item.put("mediaUrl", normalized);
    item.put("coverUrl", coverUrl);
    item.put("authorName", authorName);
    item.put("videoId", extractVideoId(pageUrl));
    results.put(item);
  }

  private void addImageResult(String pageUrl, String title, JSONArray imageUrls,
      String authorName) throws Exception {
    if (imageUrls == null || imageUrls.length() == 0) return;
    JSONObject item = new JSONObject();
    item.put("pageUrl", pageUrl);
    item.put("title", title);
    item.put("mediaUrl", "");
    item.put("coverUrl", imageUrls.optString(0, ""));
    item.put("authorName", authorName);
    item.put("videoId", extractVideoId(pageUrl));
    item.put("imageUrls", imageUrls);
    results.put(item);
  }

  static String normalizeVideoUrl(String url) {
    return url.replace("playwm", "play").replace("http://", "https://");
  }

  private void finishWithResults() {
    Intent data = new Intent();
    data.putExtra(EXTRA_RESULT, results.toString());
    setResult(RESULT_OK, data);
    finish();
  }

  private static boolean isDownloadUrl(String url) {
    return url != null && (url.startsWith("https://") || url.startsWith("http://"));
  }

  private String extractionScript() {
    String expected = expectedVideoId == null ? "" : expectedVideoId.replace("\\", "").replace("'", "");
    String secUid = extractSecUserId(webView.getUrl());
    return "(function(){try{" +
        "var expected='" + expected + "';" +
        "var secUid='" + (secUid == null ? "" : secUid.replace("'", "")) + "';" +
        "var o={pageUrl:location.href,title:document.title||'抖音视频',mediaUrl:'',coverUrl:'',authorName:'',matchedId:'',videoPages:[],imageUrls:[]};" +
        "var seen={};var addPage=function(u){try{if(!u)return;u=new URL(u,location.href).href;" +
        "var m=u.match(/douyin\\.com\\/video\\/(\\d+)/);if(m&&!seen[u]&&o.videoPages.length<100){seen[u]=1;o.videoPages.push(u);}}catch(e){}};" +
        "var authorOf=function(x){if(!x||typeof x!=='object')return '';var a=x.author;if(!a||typeof a!=='object')return '';return String(a.sec_uid||a.uid||a.unique_id||'');};" +
        "var isAuthor=function(x){if(!secUid)return true;var au=authorOf(x);return !au||au===secUid;};" +
        "var idOf=function(x){if(!x||typeof x!=='object')return '';return String(x.aweme_id||x.awemeId||x.group_id||x.groupId||x.item_id||x.itemId||'');};" +
        "var pick=function(x,owner){if(!x||o.mediaUrl)return;if(expected&&owner&&owner!==expected)return;" +
        "if(typeof x==='string'){if(/^https?:/.test(x)&&/(douyinvod|bytevcdn|\\.mp4|playwm|play)/i.test(x)){o.mediaUrl=x;o.matchedId=owner||expected||'';}return;}" +
        "if(Array.isArray(x)){for(var i=0;i<x.length&&!o.mediaUrl;i++)pick(x[i],owner);return;}" +
        "if(typeof x==='object')pick(x.url_list||x.urlList||x.uri||x.url||[],owner);};" +
        "var walk=function(x,owner){if(!x||typeof x!=='object')return;var oid=idOf(x)||owner||'';" +
        "if(oid&&/^\\d{8,}$/.test(oid)&&isAuthor(x))addPage('https://www.douyin.com/video/'+oid);" +
        "if(!o.mediaUrl&&(!expected||oid===expected)){var ks=['playApi','play_addr','playAddr','download_addr','downloadAddr'];for(var j=0;j<ks.length&&!o.mediaUrl;j++){if(x[ks[j]])pick(x[ks[j]],oid);}}" +
        "if(Array.isArray(x)){for(var i=0;i<x.length;i++)walk(x[i],oid);}" +
        "else{for(var k in x){if(k.length<80)walk(x[k],oid);}}};" +
        "var parseText=function(t){if(!t)return;try{walk(JSON.parse(t),'');}catch(e){try{walk(JSON.parse(decodeURIComponent(t)),'');}catch(e2){}}" +
        "var rx=[/[\\\"']aweme_id[\\\"']\\s*:\\s*[\\\"']?(\\d{8,})/g,/[\\\"']awemeId[\\\"']\\s*:\\s*[\\\"']?(\\d{8,})/g,/[\\\"']group_id[\\\"']\\s*:\\s*[\\\"']?(\\d{8,})/g];" +
        "for(var r=0;r<rx.length;r++){var m;while((m=rx[r].exec(t))&&o.videoPages.length<100)addPage('https://www.douyin.com/video/'+m[1]);}};" +
        "var sc=document.querySelectorAll('script');for(var si=0;si<sc.length;si++){parseText(sc[si].textContent||'');}" +
        "if(window.__INITIAL_STATE__)walk(window.__INITIAL_STATE__,'');if(window.SIGI_STATE)walk(window.SIGI_STATE,'');" +
        "var a=document.querySelectorAll('a[href]');for(var k=0;k<a.length&&o.videoPages.length<100;k++)addPage(a[k].href);" +
        "if(!o.mediaUrl){var v=document.querySelectorAll('video');for(var i=0;i<v.length;i++){var u=v[i].currentSrc||v[i].src;if(u&&/^https?:/.test(u)){o.mediaUrl=u;o.matchedId=expected||'';o.coverUrl=v[i].poster||'';break;}}}" +
        "var images=document.querySelectorAll('img');var imageSeen={};for(var ii=0;ii<images.length&&o.imageUrls.length<30;ii++){var src=images[ii].currentSrc||images[ii].src||images[ii].getAttribute('data-src')||'';try{src=new URL(src,location.href).href;}catch(e){}if(/^https?:/.test(src)&&/(douyinpic|byteimg|douyinstatic)/i.test(src)&&!imageSeen[src]){imageSeen[src]=1;o.imageUrls.push(src);}}" +
        "if(!o.coverUrl){var meta=document.querySelector('meta[property=\"og:image\"]');if(meta)o.coverUrl=meta.content||'';}" +
        "var author=document.querySelector('[data-e2e=\"user-name\"],a[href*=\"/user/\"] span');if(author)o.authorName=(author.textContent||'').trim();" +
        "if(o.mediaUrl)o.mediaUrl=o.mediaUrl.replace(/playwm/g,'play').replace(/^http:/,'https:');" +
        "return JSON.stringify(o);}catch(x){return JSON.stringify({error:String(x),pageUrl:location.href,videoPages:[]});}})();";
  }

  private static String extractSecUserId(String url) {
    if (url == null) return null;
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("/user/([^/?]+)").matcher(url);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static String extractVideoId(String url) {
    if (url == null) return null;
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("/(?:video|note)/(\\d+)|[?&](?:modal_id|aweme_id|item_id|vid)=(\\d+)|/share/video/(\\d+)")
        .matcher(url);
    if (!matcher.find()) return null;
    for (int i = 1; i <= matcher.groupCount(); i++) if (matcher.group(i) != null) return matcher.group(i);
    return null;
  }

  private Button button(String text) {
    Button button = new Button(this);
    button.setText(text);
    button.setTextSize(14);
    button.setAllCaps(false);
    button.setMinHeight(dp(44));
    return button;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override public void onBackPressed() {
    if (webView != null && webView.canGoBack() && !batchRunning) webView.goBack();
    else super.onBackPressed();
  }

  @Override protected void onDestroy() {
    CookieStore.save(this, Platform.DOUYIN);
    handler.removeCallbacksAndMessages(null);
    if (webView != null) webView.destroy();
    super.onDestroy();
  }
}
