package com.liuying.video;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public final class LoginActivity extends Activity {
  static final String EXTRA_PLATFORM = "platform";
  private WebView webView;
  private Platform platform;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    Platform platform;
    try {
      platform = Platform.valueOf(getIntent().getStringExtra(EXTRA_PLATFORM));
    } catch (Exception ignored) {
      platform = Platform.DOUYIN;
    }

    this.platform = platform;
    final Platform selectedPlatform = platform;
    DiagnosticLog.i(this, "Login", "platform login started platform=" + platform.name());
    CookieStore.restore(this, selectedPlatform);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.WHITE);

    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(8), dp(4), dp(12), dp(4));
    TextView close = textButton("‹");
    close.setTextSize(32);
    close.setContentDescription("关闭登录页");
    close.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) { finish(); }
    });
    TextView title = new TextView(this);
    title.setText(platform.displayName + " 登录");
    title.setTextSize(18);
    title.setTextColor(Color.rgb(32, 33, 36));
    bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
    bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
    root.addView(bar);

    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

    webView = new WebView(this);
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
    webView.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(
          WebView view, WebResourceRequest request) {
        return handleUrlLoading(request == null || request.getUrl() == null
            ? null : request.getUrl().toString());
      }

      @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return handleUrlLoading(url);
      }

      @Override public void onReceivedError(
          WebView view, WebResourceRequest request, WebResourceError error) {
        if (request != null && request.isForMainFrame()) {
          String url = request.getUrl() == null ? null : request.getUrl().toString();
          int errorCode = error == null ? 0 : error.getErrorCode();
          logMainDocumentError(url, errorCode);
          showMainDocumentError(view);
        }
      }

      @SuppressWarnings("deprecation")
      @Override public void onReceivedError(
          WebView view, int errorCode, String description, String failingUrl) {
        if (isCurrentMainDocument(view, failingUrl)) {
          logMainDocumentError(failingUrl, errorCode);
          showMainDocumentError(view);
        }
      }

      @Override public void onPageFinished(WebView view, String url) {
        CookieManager.getInstance().flush();
        CookieStore.save(LoginActivity.this, selectedPlatform);
      }
    });
    webView.setWebChromeClient(new WebChromeClient() {
      @Override public void onProgressChanged(WebView view, int value) {
        progress.setProgress(value);
        progress.setVisibility(value >= 100 ? View.GONE : View.VISIBLE);
      }
    });
    root.addView(webView, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    setContentView(root);
    webView.loadUrl(platform.loginUrl);
  }

  private boolean handleUrlLoading(String url) {
    if (url != null && isHttpOrHttps(url) && platform.allowsUrl(url)) {
      return false;
    }

    if (url == null || url.trim().length() == 0) {
      Toast.makeText(this, "无法打开空链接", Toast.LENGTH_SHORT).show();
      return true;
    }

    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
      startActivity(intent);
      Toast.makeText(this, "已使用系统应用打开链接", Toast.LENGTH_SHORT).show();
    } catch (ActivityNotFoundException exception) {
      DiagnosticLog.e(this, "Login", "external open failed platform=" + platform.name()
          + " url=" + url, exception);
      Toast.makeText(this, "没有可打开此链接的系统应用", Toast.LENGTH_LONG).show();
    } catch (RuntimeException exception) {
      DiagnosticLog.e(this, "Login", "external open failed platform=" + platform.name()
          + " url=" + url, exception);
      Toast.makeText(this, "无法打开此链接", Toast.LENGTH_LONG).show();
    }
    return true;
  }

  private boolean isHttpOrHttps(String url) {
    Uri uri = Uri.parse(url);
    String scheme = uri.getScheme();
    return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
  }

  private boolean isCurrentMainDocument(WebView view, String failingUrl) {
    if (view == null || failingUrl == null) return false;
    String currentUrl = view.getUrl();
    return failingUrl.equals(currentUrl) || failingUrl.equals(view.getOriginalUrl());
  }

  private void logMainDocumentError(String url, int errorCode) {
    DiagnosticLog.e(this, "Login", "main document network error platform=" + platform.name()
        + " errorCode=" + errorCode + " url=" + url);
  }

  private void showMainDocumentError(WebView view) {
    String service = platform == Platform.YOUTUBE ? "YouTube/Google" : platform.displayName;
    String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
        + "<body style=\"font-family:sans-serif;padding:24px;color:#202124\">"
        + "<h2>网络连接失败</h2><p>无法连接到 " + escapeHtml(service) + "。</p>"
        + "<p>请检查网络连接或确认当前网络可以访问该服务,然后返回重试。</p>"
        + "</body></html>";
    view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
  }

  private String escapeHtml(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  @Override public void onBackPressed() {
    if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
  }

  @Override protected void onPause() {
    CookieStore.save(this, platform);
    CookieManager.getInstance().flush();
    super.onPause();
  }

  @Override protected void onDestroy() {
    CookieStore.save(this, platform);
    if (webView != null) webView.destroy();
    super.onDestroy();
  }

  private TextView textButton(String label) {
    TextView view = new TextView(this);
    view.setText(label);
    view.setGravity(android.view.Gravity.CENTER);
    view.setTextColor(Color.rgb(25, 112, 95));
    view.setBackgroundResource(android.R.drawable.list_selector_background);
    return view;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
