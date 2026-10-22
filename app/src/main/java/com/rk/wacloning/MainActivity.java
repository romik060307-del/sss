package com.rk.wacloning;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A phone-first wrapper for the official WhatsApp Web site. */
public class MainActivity extends Activity {
    private static final String WHATSAPP_WEB = "https://web.whatsapp.com/";
    private static final String PREF_DESKTOP = "desktop_mode";
    private static final String PREF_COMPACT_HEADER = "compact_header";
    private static final String PREF_LIGHT_MODE = "light_mode";
    private static final int REQUEST_FILE_PICKER = 301;
    private static final int REQUEST_WEB_PERMISSIONS = 302;
    private static final int REQUEST_DOWNLOAD_PERMISSION = 303;
    private static final int REQUEST_BLOB_PERMISSION = 304;
    private static final int REQUEST_CALL_PERMISSIONS = 305;
    private static final int REQUEST_EXPORT_PERMISSION = 306;
    private static final String PREF_CALL_PERMISSION_ASKED = "call_permission_asked";
    private static final long MAX_BLOB_DOWNLOAD_BYTES = 40L * 1024L * 1024L;
    private static final int BLOB_CHUNK_BYTES = 256 * 1024;
    private static final int MAX_MEDIA_CANDIDATES = 24;

    private LinearLayout rootLayout;
    private WebView webView;
    private android.widget.ImageView toolbarAppIcon;
    private TextView toolbarBackButton;
    private TextView toolbarMenuButton;
    private HorizontalScrollView bottomNavScroll;
    private LinearLayout bottomNavLayout;
    private ProgressBar pageProgress;
    private LinearLayout toolbarRoot;
    private TextView toolbarSubtitle;
    private LinearLayout toolbarTitles;
    private boolean compactHeader = true;
    private boolean lightMode = false;
    private Dialog menuDialog;
    // v2.0: dedicated "layar fitur" = WhatsApp's real desktop layout (landscape, zoomable) for sidebar features and calls.
    private boolean featureScreen = false;
    private boolean desktopMode = true;
    private boolean backCheckPending = false;
    private boolean keyboardVisible = false;
    private String currentScreenMode = "login";

    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingWebPermission;
    // v1.9: WhatsApp Web opens its call screen with window.open(); it needs a real second WebView.
    private Dialog popupDialog;
    private WebView popupWebView;
    private Runnable popupShowTask;
    private final Handler popupHandler = new Handler(Looper.getMainLooper());
    private String[] pendingWebResources;
    private PendingDownload pendingDownload;

    private int pendingBlobMediaId = -1;
    private String pendingBlobMime = "application/octet-stream";
    private String pendingBlobName = "WA-Cloning-Media";

    private AlertDialog blobProgressDialog;
    private ProgressBar blobProgressBar;
    private TextView blobProgressText;
    private Uri blobOutputUri;
    private File blobOutputFile;
    private OutputStream blobOutputStream;
    private String blobOutputName;
    private String blobOutputMime;
    private long blobTotalBytes;
    private long blobWrittenBytes;

    // v2.1: chat export. exportDir is a private cache folder filled by export-chat.js, then zipped into Downloads.
    private volatile boolean exporting = false;
    private volatile File exportDir;
    private AlertDialog exportDialog;
    private TextView exportText;
    private Boolean pendingExportWithMedia;
    private boolean blobCancelled = false;

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;

        PendingDownload(String url, String userAgent, String contentDisposition, String mimeType) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
        }
    }

    private static final class MediaCandidate {
        final int id;
        final String type;
        final String url;
        final int width;
        final int height;
        final String mime;

        MediaCandidate(int id, String type, String url, int width, int height, String mime) {
            this.id = id;
            this.type = type;
            this.url = url;
            this.width = width;
            this.height = height;
            this.mime = mime;
        }

        String label() {
            String name = "video".equals(type) ? "Video" : "Foto";
            String source = url.startsWith("blob:") ? "media yang sedang dibuka" : "tautan media";
            return name + "  •  " + width + "×" + height + "\n" + source;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7, 94, 84));
        getWindow().setNavigationBarColor(Color.rgb(14, 26, 23));
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        desktopMode = getPreferences(MODE_PRIVATE).getBoolean(PREF_DESKTOP, true);
        compactHeader = getPreferences(MODE_PRIVATE).getBoolean(PREF_COMPACT_HEADER, true);
        lightMode = getPreferences(MODE_PRIVATE).getBoolean(PREF_LIGHT_MODE, false);

        buildUi();
        configureWebView();

        boolean restored = false;
        if (savedInstanceState != null) {
            try {
                restored = webView.restoreState(savedInstanceState) != null;
            } catch (Exception ignored) {
                restored = false;
            }
        }
        if (!restored) {
            // Do not clear WebView cookies, cache, or DOM storage. WhatsApp's own local session lives there.
            webView.loadUrl(WHATSAPP_WEB);
        }
    }

    private void buildUi() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.WHITE);
        rootLayout.setFocusableInTouchMode(true);

        LinearLayout toolbar = new LinearLayout(this);
        toolbarRoot = toolbar;
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), 0, dp(8), 0);
        toolbar.setBackgroundColor(Color.rgb(7, 94, 84));
        toolbar.setElevation(dp(2));

        toolbarBackButton = new TextView(this);
        toolbarBackButton.setText("‹");
        toolbarBackButton.setTextSize(34);
        toolbarBackButton.setTextColor(Color.WHITE);
        toolbarBackButton.setGravity(Gravity.CENTER);
        toolbarBackButton.setContentDescription("Kembali ke daftar chat");
        toolbarBackButton.setBackground(makeRipple(Color.TRANSPARENT, dp(22)));
        toolbarBackButton.setVisibility(View.GONE);
        toolbar.addView(toolbarBackButton, new LinearLayout.LayoutParams(dp(40), dp(48)));
        toolbarBackButton.setOnClickListener(v -> returnToChatListFromMenu());

        toolbarAppIcon = new android.widget.ImageView(this);
        toolbarAppIcon.setImageResource(R.mipmap.ic_launcher);
        toolbarAppIcon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconParams.rightMargin = dp(10);
        toolbar.addView(toolbarAppIcon, iconParams);

        LinearLayout titles = new LinearLayout(this);
        toolbarTitles = titles;
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(isSecondProfile() ? "WA Cloning 2" : "WA Cloning 1");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = new TextView(this);
        toolbarSubtitle = subtitle;
        subtitle.setText(profileSubtitle());
        subtitle.setTextColor(Color.rgb(219, 243, 235));
        subtitle.setTextSize(10.5f);
        titles.addView(title);
        titles.addView(subtitle);
        toolbar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView menu = new TextView(this);
        toolbarMenuButton = menu;
        menu.setText("⋮");
        menu.setTextSize(27);
        menu.setTextColor(Color.WHITE);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Pengaturan aplikasi");
        menu.setBackground(makeRipple(Color.TRANSPARENT, dp(20)));
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(48)));
        menu.setOnClickListener(v -> showMenu(menu));
        rootLayout.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(compactHeader ? 40 : 54)));
        applyToolbarAppearance();

        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setProgress(0);
        pageProgress.setVisibility(View.GONE);
        pageProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(31, 210, 102)));
        rootLayout.addView(pageProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setSaveEnabled(true);
        rootLayout.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Phone-friendly navigation: the desktop left rail is hidden in portrait and its sections are exposed here.
        bottomNavScroll = new HorizontalScrollView(this);
        bottomNavScroll.setHorizontalScrollBarEnabled(false);
        bottomNavScroll.setFillViewport(true);
        bottomNavScroll.setBackgroundColor(Color.rgb(250, 250, 250));
        bottomNavLayout = new LinearLayout(this);
        bottomNavLayout.setOrientation(LinearLayout.HORIZONTAL);
        bottomNavLayout.setGravity(Gravity.CENTER_VERTICAL);
        bottomNavLayout.setWeightSum(7f);
        String[][] navItems = new String[][] {
            {"☰", "Chat", "chats"}, {"☎", "Panggilan", "calls"}, {"◉", "Status", "status"},
            {"◎", "Saluran", "channels"}, {"♧", "Komunitas", "communities"}, {"▧", "Media", "media"},
            {"☺", "Profil", "profile"}
        };
        for (String[] item : navItems) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(1), dp(3), dp(1), dp(2));
            TextView glyph = new TextView(this);
            glyph.setText(item[0]); glyph.setTextSize(16); glyph.setTextColor(Color.rgb(7, 94, 84)); glyph.setGravity(Gravity.CENTER);
            TextView label = new TextView(this);
            label.setText(item[1]); label.setSingleLine(true); label.setTextSize(8); label.setTextColor(Color.rgb(65, 75, 72)); label.setGravity(Gravity.CENTER);
            cell.addView(glyph); cell.addView(label);
            cell.setContentDescription(item[1]);
            cell.setBackground(makeRipple(Color.TRANSPARENT, 8));
            cell.setOnClickListener(v -> {
                navigateWhatsAppSection(item[2], item[1]);
            });
            bottomNavLayout.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        bottomNavScroll.addView(bottomNavLayout, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(bottomNavScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        setContentView(rootLayout);
        installKeyboardWatcher();
    }

    private android.graphics.drawable.Drawable makeRipple(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.rgb(220, 237, 230)), shape, null);
        }
        return shape;
    }

    private void installKeyboardWatcher() {
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (rootLayout == null) return;
            Rect visible = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
            int rootHeight = getWindow().getDecorView().getRootView().getHeight();
            keyboardVisible = rootHeight - visible.bottom > dp(170);
            if (bottomNavScroll != null && !featureScreen && !"login".equals(currentScreenMode)) {
                bottomNavScroll.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void configureWebView() {
        webView.addJavascriptInterface(new PhoneUiBridge(), "WACloningApp");
        webView.addJavascriptInterface(new ExportBridge(), "WACloningExport");
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        // Honor the page viewport metadata; the portrait adapter swaps panes instead of scaling.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setTextZoom(100);
        // v1.9: call audio/video starts without a tap on the media element, so autoplay must be allowed.
        settings.setMediaPlaybackRequiresUserGesture(false);
        // v1.9: required for WhatsApp Web voice/video call windows (window.open -> onCreateWindow).
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        applyUserAgent();

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !request.isForMainFrame()) return false;
                return routeTopLevelUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeTopLevelUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageProgress.setVisibility(View.VISIBLE);
                pageProgress.setProgress(8);
                injectDesktopCompat(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageProgress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                injectDesktopCompat(view);
                injectPhoneLayoutFix();
                // A reload while the feature screen is open must put the fresh page back into desktop layout.
                if (featureScreen) view.postDelayed(MainActivity.this::applyDesktopFeatureJs, 600);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this,
                            "WhatsApp Web gagal dimuat. Periksa internet, lalu tekan Muat ulang.", Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                pageProgress.setProgress(newProgress);
                pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return launchFileChooser(callback, params);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) {
                    pendingWebPermission = null;
                    pendingWebResources = null;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                return openPopupWindow(resultMsg);
            }

            @Override
            public void onCloseWindow(WebView window) {
                runOnUiThread(() -> closePopupWindow());
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                Toast.makeText(MainActivity.this,
                        "Tautan ini bukan unduhan langsung. Buka Unduh media untuk mencari foto/video yang sedang tampil.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            startDownload(new PendingDownload(url, userAgent, contentDisposition, mimeType));
        });
    }

    /** Uses Android's WebView FileChooserParams so accept types, multi-select and capture hints are preserved. */
    private boolean launchFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (callback == null) return false;
        if (fileChooserCallback != null) {
            try { fileChooserCallback.onReceiveValue(null); } catch (Exception ignored) { }
        }
        fileChooserCallback = callback;
        try {
            Intent pick;
            if (params != null) {
                pick = params.createIntent();
                if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
            } else {
                pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(pick, REQUEST_FILE_PICKER);
            return true;
        } catch (ActivityNotFoundException | IllegalArgumentException | SecurityException e) {
            fileChooserCallback = null;
            try { callback.onReceiveValue(null); } catch (Exception ignored) { }
            Toast.makeText(this, "Pemilih foto/video/file tidak tersedia di perangkat ini.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private final class PhoneUiBridge {
        @JavascriptInterface
        public void onPhoneScreenChanged(String mode) {
            runOnUiThread(() -> applyPhoneScreen(mode));
        }
    }

    private void applyPhoneScreen(String mode) {
        currentScreenMode = mode == null ? "login" : mode;
        // The feature screen owns the toolbar and hides the bottom bar; ignore adapter notifications while it is open.
        if (featureScreen) return;
        boolean detail = "chat".equals(mode) || "panel".equals(mode);
        if (toolbarBackButton != null) toolbarBackButton.setVisibility(detail ? View.VISIBLE : View.GONE);
        applyToolbarAppearance();
        if (bottomNavScroll != null) bottomNavScroll.setVisibility("login".equals(mode) ? View.GONE : View.VISIBLE);
        if (bottomNavScroll != null && keyboardVisible) bottomNavScroll.setVisibility(View.GONE);
        maybeAskCallPermissions(currentScreenMode);
    }

    private void injectPhoneLayoutFix() {
        if (webView == null) return;
        try (InputStream input = getAssets().open("phone-layout.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            webView.evaluateJavascript(new String(output.toByteArray(), StandardCharsets.UTF_8), value -> {
                if (webView != null) webView.evaluateJavascript("window.__rkSetLightMode && window.__rkSetLightMode(" + lightMode + ")", null);
            });
        } catch (IOException e) {
            Toast.makeText(this, "Pengaturan layar HP tidak dapat dimuat.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean routeTopLevelUrl(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https"))) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                Toast.makeText(this, "Tautan ini tidak dapat dibuka di perangkat.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("whatsapp.com") || host.endsWith(".whatsapp.com") || host.equals("whatsapp.net") || host.endsWith(".whatsapp.net")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            Toast.makeText(this, "Tidak ada browser untuk membuka tautan itu.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (request == null) return;
        String host = request.getOrigin() == null ? "" : request.getOrigin().getHost();
        boolean fromWhatsApp = host != null && (host.equals("web.whatsapp.com") || host.endsWith(".whatsapp.com"));
        // The call window is opened by WhatsApp with an empty origin; trust it only while that popup exists.
        boolean fromCallPopup = popupWebView != null && (host == null || host.isEmpty());
        if (!fromWhatsApp && !fromCallPopup) {
            request.deny();
            return;
        }
        ArrayList<String> needed = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) &&
                    checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA);
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (needed.isEmpty()) {
            request.grant(request.getResources());
        } else {
            pendingWebPermission = request;
            pendingWebResources = request.getResources();
            requestPermissions(needed.toArray(new String[0]), REQUEST_WEB_PERMISSIONS);
        }
    }

    private void startDownload(PendingDownload download) {
        if (download == null) return;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = download;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_DOWNLOAD_PERMISSION);
            return;
        }
        enqueueDownload(download);
    }

    private void enqueueDownload(PendingDownload download) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(download.url));
            String filename = URLUtil.guessFileName(download.url, download.contentDisposition, download.mimeType);
            filename = sanitizeFilename(filename);
            if (filename.trim().isEmpty()) filename = "wa-media-download";
            request.setTitle(filename);
            request.setDescription("Mengunduh melalui WA Cloning");
            if (download.mimeType != null && !download.mimeType.trim().isEmpty()) request.setMimeType(download.mimeType);
            if (download.userAgent != null && !download.userAgent.isEmpty()) request.addRequestHeader("User-Agent", download.userAgent);
            request.addRequestHeader("Referer", WHATSAPP_WEB);
            String cookie = CookieManager.getInstance().getCookie(download.url);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager tidak tersedia");
            manager.enqueue(request);
            Toast.makeText(this, "Unduhan dimulai. Cek folder Download.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Unduhan langsung gagal. Media ini mungkin memakai tautan sementara WhatsApp.", Toast.LENGTH_LONG).show();
        }
    }

    private void openDownloadCenter() {
        if (webView == null || !isWhatsAppHost(webView.getUrl())) {
            showInfo("Unduh media", "Buka WhatsApp Web dan tampilkan foto, video, atau Status yang ingin kamu simpan. Kemudian tekan Unduh media.");
            return;
        }
        String script = "(function(){try{" +
                "var visible=function(e){var r=e.getBoundingClientRect(),s=getComputedStyle(e);return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity||1)>0&&r.width>=110&&r.height>=90&&r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth;};" +
                "var dialogs=Array.from(document.querySelectorAll('[role=dialog]')).filter(visible);" +
                "var blocked=dialogs.some(function(d){return /view once|sekali lihat|hanya dapat dilihat satu kali|view one time/i.test(d.innerText||'');});" +
                "if(blocked)return JSON.stringify({blocked:true,items:[]});" +
                "var items=[],seen={};" +
                "var add=function(e,t){if(items.length>=" + MAX_MEDIA_CANDIDATES + ")return; if(!visible(e))return;" +
                "var u=(t==='video'?(e.currentSrc||e.src):(e.currentSrc||e.src))||'';if(!u||!(u.indexOf('https://')===0||u.indexOf('http://')===0||u.indexOf('blob:')===0))return;" +
                "var w=Math.round(e.videoWidth||e.naturalWidth||e.clientWidth||0),h=Math.round(e.videoHeight||e.naturalHeight||e.clientHeight||0);" +
                "if(w<140||h<100)return;var key=t+':'+u;if(seen[key])return;seen[key]=1;" +
                "items.push({id:items.length,type:t,url:u,width:w,height:h,mime:(e.currentType||e.type||'')});};" +
                "Array.from(document.querySelectorAll('video')).forEach(function(e){add(e,'video');});" +
                "Array.from(document.querySelectorAll('img')).forEach(function(e){add(e,'photo');});" +
                "window.__rkMediaItems=items;return JSON.stringify({blocked:false,items:items});" +
                "}catch(e){return JSON.stringify({blocked:false,items:[],error:true});}})()";
        webView.evaluateJavascript(script, value -> {
            try {
                String jsonText = decodeJsString(value);
                if (jsonText == null) throw new JSONException("No media data");
                JSONObject result = new JSONObject(jsonText);
                if (result.optBoolean("blocked", false)) {
                    showInfo("Media sekali lihat", "WA Cloning tidak menyimpan pesan atau media sekali lihat. Ini menjaga batasan privasi WhatsApp.");
                    return;
                }
                JSONArray jsonItems = result.optJSONArray("items");
                ArrayList<MediaCandidate> candidates = new ArrayList<>();
                if (jsonItems != null) {
                    for (int i = 0; i < jsonItems.length(); i++) {
                        JSONObject item = jsonItems.optJSONObject(i);
                        if (item == null) continue;
                        String url = item.optString("url", "");
                        if (url.isEmpty()) continue;
                        candidates.add(new MediaCandidate(item.optInt("id", i), item.optString("type", "photo"), url,
                                item.optInt("width", 0), item.optInt("height", 0), item.optString("mime", "")));
                    }
                }
                if (candidates.isEmpty()) {
                    showInfo("Belum ada media", "Tampilkan foto/video atau buka Status yang ingin disimpan sampai medianya terlihat di layar. Lalu tekan Unduh media lagi. Tidak semua media WhatsApp menyediakan unduhan langsung.");
                    return;
                }
                String[] labels = new String[candidates.size()];
                for (int i = 0; i < candidates.size(); i++) labels[i] = candidates.get(i).label();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Foto & video yang terlihat")
                        .setItems(labels, (dialog, which) -> startSelectedMedia(candidates.get(which)))
                        .setNegativeButton("Batal", null)
                        .show();
            } catch (Exception e) {
                showInfo("Unduh media", "Media tidak dapat dideteksi di halaman ini. Buka foto, video, atau Status yang ingin disimpan, lalu coba lagi.");
            }
        });
    }

    private void startSelectedMedia(MediaCandidate media) {
        if (media.url.startsWith("blob:")) {
            String fallbackMime = media.mime;
            if (fallbackMime == null || !fallbackMime.contains("/")) {
                fallbackMime = "video".equals(media.type) ? "video/mp4" : "image/jpeg";
            }
            String extension = extensionForMime(fallbackMime, media.type);
            String name = "WA-Cloning-" + ("video".equals(media.type) ? "Video" : "Foto") + "-" + System.currentTimeMillis() + extension;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingBlobMediaId = media.id;
                pendingBlobMime = fallbackMime;
                pendingBlobName = name;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_BLOB_PERMISSION);
                return;
            }
            beginBlobDownload(media.id, fallbackMime, name);
        } else if (media.url.startsWith("http://") || media.url.startsWith("https://")) {
            String mime = media.mime;
            if (mime == null || !mime.contains("/")) mime = "video".equals(media.type) ? "video/mp4" : "image/jpeg";
            String userAgent = webView == null ? WebSettings.getDefaultUserAgent(this) : webView.getSettings().getUserAgentString();
            startDownload(new PendingDownload(media.url, userAgent, null, mime));
        } else {
            Toast.makeText(this, "Format media ini belum dapat diunduh.", Toast.LENGTH_LONG).show();
        }
    }

    private void beginBlobDownload(int mediaId, String mime, String filename) {
        if (webView == null || blobProgressDialog != null) return;
        blobCancelled = false;
        String script = "(function(){try{" +
                "window.__rkBlobReady=false;window.__rkBlobError='';window.__rkDownloadBlob=null;" +
                "var x=window.__rkMediaItems&&window.__rkMediaItems[" + mediaId + "];if(!x||!x.url||x.url.indexOf('blob:')!==0)return 'NOT_FOUND';" +
                "fetch(x.url).then(function(r){if(!r.ok)throw new Error('fetch');return r.blob();}).then(function(b){" +
                "if(!b.size)window.__rkBlobError='EMPTY';else if(b.size>" + MAX_BLOB_DOWNLOAD_BYTES + ")window.__rkBlobError='TOO_LARGE';" +
                "else{window.__rkDownloadBlob=b;window.__rkBlobReady=true;}}).catch(function(e){window.__rkBlobError='UNAVAILABLE';});return 'STARTED';" +
                "}catch(e){return 'ERROR';}})()";
        webView.evaluateJavascript(script, value -> {
            String state = decodeJsString(value);
            if (!"STARTED".equals(state)) {
                showInfo("Unduh media", "WhatsApp tidak menyediakan data media ini untuk diunduh secara langsung.");
                return;
            }
            pollBlobMetadata(mediaId, mime, filename, 0);
        });
    }

    private void pollBlobMetadata(int mediaId, String mime, String filename, int attempt) {
        if (blobCancelled || webView == null) return;
        String script = "(function(){if(window.__rkBlobError)return 'ERR:'+window.__rkBlobError;" +
                "if(!window.__rkBlobReady||!window.__rkDownloadBlob)return 'PENDING';" +
                "return JSON.stringify({size:window.__rkDownloadBlob.size,mime:window.__rkDownloadBlob.type||''});})()";
        webView.evaluateJavascript(script, value -> {
            String decoded = decodeJsString(value);
            if (decoded == null || "PENDING".equals(decoded)) {
                if (attempt > 180) {
                    failBlobDownload("Waktu tunggu media habis. Coba buka ulang media lalu unduh lagi.");
                } else {
                    webView.postDelayed(() -> pollBlobMetadata(mediaId, mime, filename, attempt + 1), 100);
                }
                return;
            }
            if (decoded.startsWith("ERR:")) {
                String reason = decoded.substring(4);
                if ("TOO_LARGE".equals(reason)) failBlobDownload("Ukuran media melebihi batas 40 MB agar aplikasi tetap ringan.");
                else failBlobDownload("Media ini tidak dapat diambil sebagai file. Coba unduhan bawaan WhatsApp jika tersedia.");
                return;
            }
            try {
                JSONObject meta = new JSONObject(decoded);
                long size = meta.getLong("size");
                String detectedMime = meta.optString("mime", "");
                if (size <= 0 || size > MAX_BLOB_DOWNLOAD_BYTES) {
                    failBlobDownload("Ukuran media tidak didukung atau melebihi 40 MB.");
                    return;
                }
                String safeMime = detectedMime.contains("/") ? detectedMime : mime;
                prepareBlobOutput(filename, safeMime);
                blobTotalBytes = size;
                blobWrittenBytes = 0;
                showBlobProgress(filename, size);
                requestBlobChunk(0, 0);
            } catch (Exception e) {
                failBlobDownload("Informasi file media tidak terbaca.");
            }
        });
    }

    private void prepareBlobOutput(String filename, String mime) throws IOException {
        blobOutputName = sanitizeFilename(filename);
        blobOutputMime = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
        blobOutputUri = null;
        blobOutputFile = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, blobOutputName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, blobOutputMime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WA Cloning");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            blobOutputUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (blobOutputUri == null) throw new IOException("Tidak bisa membuat file Downloads");
            blobOutputStream = getContentResolver().openOutputStream(blobOutputUri, "w");
            if (blobOutputStream == null) throw new IOException("Tidak bisa membuka file Downloads");
        } else {
            File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Folder Download tidak tersedia");
            blobOutputFile = uniqueFile(directory, blobOutputName);
            blobOutputStream = new FileOutputStream(blobOutputFile);
        }
    }

    private File uniqueFile(File directory, String desiredName) {
        File file = new File(directory, desiredName);
        if (!file.exists()) return file;
        int dot = desiredName.lastIndexOf('.');
        String base = dot > 0 ? desiredName.substring(0, dot) : desiredName;
        String ext = dot > 0 ? desiredName.substring(dot) : "";
        int count = 1;
        do { file = new File(directory, base + " (" + count++ + ")" + ext); } while (file.exists() && count < 1000);
        return file;
    }

    private void showBlobProgress(String filename, long size) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(14), dp(22), dp(8));
        blobProgressText = new TextView(this);
        blobProgressText.setText("Menyimpan " + filename + "…\n0% dari " + formatBytes(size));
        blobProgressText.setTextColor(Color.rgb(35, 48, 43));
        blobProgressText.setTextSize(13);
        blobProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        blobProgressBar.setMax(1000);
        blobProgressBar.setProgress(0);
        blobProgressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(31, 190, 93)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        barParams.topMargin = dp(14);
        content.addView(blobProgressText);
        content.addView(blobProgressBar, barParams);
        blobProgressDialog = new AlertDialog.Builder(this)
                .setTitle("Menyimpan media")
                .setView(content)
                .setNegativeButton("Batal", (dialog, which) -> cancelBlobDownload())
                .create();
        blobProgressDialog.setCanceledOnTouchOutside(false);
        blobProgressDialog.setOnCancelListener(dialog -> cancelBlobDownload());
        blobProgressDialog.show();
    }

    private void requestBlobChunk(long offset, int attempt) {
        if (blobCancelled || blobOutputStream == null || webView == null) return;
        if (offset >= blobTotalBytes) {
            finishBlobDownload();
            return;
        }
        long end = Math.min(offset + BLOB_CHUNK_BYTES, blobTotalBytes);
        String startScript = "(function(){try{var b=window.__rkDownloadBlob;if(!b)return 'NO_BLOB';" +
                "window.__rkChunkReady=false;window.__rkChunkResult='';window.__rkChunkError='';" +
                "var r=new FileReader();r.onload=function(){window.__rkChunkResult=String(r.result).split(',')[1]||'';window.__rkChunkReady=true;};" +
                "r.onerror=function(){window.__rkChunkError='READ';};r.readAsDataURL(b.slice(" + offset + "," + end + "));return 'STARTED';" +
                "}catch(e){return 'ERROR';}})()";
        webView.evaluateJavascript(startScript, value -> {
            String state = decodeJsString(value);
            if (!"STARTED".equals(state)) {
                failBlobDownload("Gagal membaca bagian file media.");
                return;
            }
            pollBlobChunk(offset, end, attempt, 0);
        });
    }

    private void pollBlobChunk(long offset, long end, int chunkAttempt, int pollAttempt) {
        if (blobCancelled || webView == null) return;
        String script = "(function(){if(window.__rkChunkError){var e=window.__rkChunkError;window.__rkChunkError='';return 'ERR:'+e;}" +
                "if(!window.__rkChunkReady)return 'PENDING';var d=window.__rkChunkResult||'';" +
                "window.__rkChunkResult='';window.__rkChunkReady=false;return d;})()";
        webView.evaluateJavascript(script, value -> {
            String decoded = decodeJsString(value);
            if (decoded == null || "PENDING".equals(decoded)) {
                if (pollAttempt > 100) failBlobDownload("Membaca media terlalu lama. Coba lagi.");
                else webView.postDelayed(() -> pollBlobChunk(offset, end, chunkAttempt, pollAttempt + 1), 50);
                return;
            }
            if (decoded.startsWith("ERR:") || decoded.isEmpty()) {
                failBlobDownload("Gagal membaca media. Format ini mungkin tidak bisa diekspor dari WhatsApp Web.");
                return;
            }
            try {
                byte[] bytes = Base64.decode(decoded, Base64.DEFAULT);
                blobOutputStream.write(bytes);
                blobWrittenBytes += bytes.length;
                if (blobProgressBar != null) blobProgressBar.setProgress((int) Math.min(1000, blobWrittenBytes * 1000L / Math.max(1, blobTotalBytes)));
                if (blobProgressText != null) blobProgressText.setText("Menyimpan " + blobOutputName + "…\n" +
                        (blobWrittenBytes * 100L / Math.max(1, blobTotalBytes)) + "% dari " + formatBytes(blobTotalBytes));
                requestBlobChunk(end, 0);
            } catch (Exception e) {
                failBlobDownload("Tidak bisa menyimpan data media di folder Download.");
            }
        });
    }

    private void finishBlobDownload() {
        try {
            if (blobOutputStream != null) {
                blobOutputStream.flush();
                blobOutputStream.close();
                blobOutputStream = null;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && blobOutputUri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(blobOutputUri, values, null, null);
            }
            dismissBlobProgress();
            Toast.makeText(this, "Media tersimpan di Download/WA Cloning.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            failBlobDownload("File belum berhasil disimpan sempurna.");
            return;
        } finally {
            clearBlobJavascriptState();
        }
    }

    private void failBlobDownload(String message) {
        blobCancelled = true;
        cleanupBlobOutput();
        dismissBlobProgress();
        clearBlobJavascriptState();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void cancelBlobDownload() {
        if (blobOutputStream == null && blobProgressDialog == null) return;
        blobCancelled = true;
        cleanupBlobOutput();
        dismissBlobProgress();
        clearBlobJavascriptState();
    }

    private void cleanupBlobOutput() {
        try { if (blobOutputStream != null) blobOutputStream.close(); } catch (Exception ignored) { }
        blobOutputStream = null;
        try {
            if (blobOutputUri != null) getContentResolver().delete(blobOutputUri, null, null);
        } catch (Exception ignored) { }
        try { if (blobOutputFile != null && blobOutputFile.exists()) blobOutputFile.delete(); } catch (Exception ignored) { }
        blobOutputUri = null;
        blobOutputFile = null;
    }

    private void dismissBlobProgress() {
        if (blobProgressDialog != null) {
            try { if (blobProgressDialog.isShowing()) blobProgressDialog.dismiss(); } catch (Exception ignored) { }
        }
        blobProgressDialog = null;
        blobProgressBar = null;
        blobProgressText = null;
    }

    private void clearBlobJavascriptState() {
        if (webView != null) {
            webView.evaluateJavascript("(function(){window.__rkDownloadBlob=null;window.__rkChunkResult='';window.__rkChunkReady=false;window.__rkBlobReady=false;})()", null);
        }
    }

    private String decodeJsString(String raw) {
        if (raw == null || "null".equals(raw)) return null;
        try {
            Object decoded = new JSONTokener(raw).nextValue();
            return decoded instanceof String ? (String) decoded : decoded.toString();
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String sanitizeFilename(String filename) {
        String safe = filename == null ? "WA-Cloning-Media" : filename;
        safe = safe.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) safe = "WA-Cloning-Media";
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    private String extensionForMime(String mime, String type) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("webm")) return ".webm";
        if (m.contains("quicktime") || m.contains("mov")) return ".mov";
        if (m.contains("mp4")) return ".mp4";
        if (m.contains("png")) return ".png";
        if (m.contains("webp")) return ".webp";
        if (m.contains("gif")) return ".gif";
        if (m.contains("jpeg") || m.contains("jpg")) return ".jpg";
        return "video".equals(type) ? ".mp4" : ".jpg";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private boolean isWhatsAppHost(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("whatsapp.com") || host.endsWith(".whatsapp.com") || host.equals("whatsapp.net") || host.endsWith(".whatsapp.net");
    }

    private void applyUserAgent() {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        String current = WebSettings.getDefaultUserAgent(this);
        if (!desktopMode) {
            settings.setUserAgentString(current);
        } else {
            Matcher matcher = Pattern.compile("Chrome/([0-9.]+)").matcher(current);
            String chromeVersion = matcher.find() ? matcher.group(1) : "120.0.0.0";
            settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromeVersion + " Safari/537.36");
        }
        // Use the actual phone viewport and never overview-shrink the full desktop layout.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
    }

    private void toggleDesktopMode() {
        desktopMode = !desktopMode;
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_DESKTOP, desktopMode).apply();
        applyUserAgent();
        Toast.makeText(this, desktopMode ? "Kompatibilitas WhatsApp Web desktop aktif." :
                "User-agent seluler dicoba. Jika WhatsApp tidak mendukungnya, aktifkan mode desktop lagi.", Toast.LENGTH_LONG).show();
        if (webView != null) webView.reload();
    }

    private void navigateWhatsAppSection(String section, String label) {
        navigateWhatsAppSection(section, label, 0);
    }

    private void navigateWhatsAppSection(final String section, final String label, final int attempt) {
        if (webView == null) return;
        String safe = JSONObject.quote(section);
        webView.evaluateJavascript("(function(){return window.__rkNativeNavigate ? (window.__rkNativeNavigate(" + safe + ")?'OK':'MISS') : 'MISS';})()", value -> {
            if ("OK".equals(decodeJsString(value))) return;
            // WhatsApp renders its sidebar late; retry twice before reporting a miss.
            if (attempt < 2 && webView != null) {
                webView.postDelayed(() -> navigateWhatsAppSection(section, label, attempt + 1), 450);
                return;
            }
            showNavMissDialog(label);
        });
    }

    private void showNavMissDialog(final String label) {
        if (webView == null || isFinishing()) return;
        webView.evaluateJavascript("(function(){return window.__rkNavDebug ? window.__rkNavDebug() : 'Skrip navigasi belum dimuat. Muat ulang halaman.';})()", value -> {
            if (isFinishing()) return;
            final String report = decodeJsString(value);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Menu " + label + " belum ditemukan")
                    .setMessage("Menu ini belum bisa dijangkau dari tampilan HP. Tekan Layar fitur untuk memakai menu aslinya "
                            + "(tampilan desktop, layar miring, bisa dicubit). Atau muat ulang halaman, atau tekan Salin dan kirim laporan ini ke pengembang.\n\n" + report)
                    .setNegativeButton("Tutup", (d, w) -> d.dismiss())
                    .setNeutralButton("Layar fitur", (d, w) -> enterFeatureScreen())
                    .setPositiveButton("Salin", (d, w) -> {
                        try {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("laporan-navigasi", report));
                                Toast.makeText(MainActivity.this, "Laporan disalin.", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception ignored) { }
                    })
                    .show();
        });
    }

    /* ---------- Call window (window.open) support, v1.9 ---------- */

    private boolean openPopupWindow(Message resultMsg) {
        if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
        closePopupWindow();

        final WebView popup = new WebView(this);
        WebSettings ps = popup.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        ps.setDatabaseEnabled(true);
        ps.setMediaPlaybackRequiresUserGesture(false);
        ps.setJavaScriptCanOpenWindowsAutomatically(true);
        ps.setSupportZoom(false);
        ps.setAllowFileAccess(false);
        ps.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (webView != null) ps.setUserAgentString(webView.getSettings().getUserAgentString());
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);

        popup.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                injectDesktopCompat(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data") || scheme.equals("javascript")) return false;
                if (uri != null && isWhatsAppHost(uri.toString())) return false;
                // A normal chat link (target=_blank): open it in the browser and drop the empty popup.
                routeTopLevelUrl(uri);
                runOnUiThread(() -> closePopupWindow());
                return true;
            }
        });
        popup.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return launchFileChooser(callback, params);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }

            @Override
            public void onCloseWindow(WebView window) {
                runOnUiThread(() -> closePopupWindow());
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message msg) {
                return false;
            }
        });

        final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(popup, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnDismissListener(d -> {
            if (popupDialog == dialog) popupDialog = null;
            destroyPopupWebView(popup);
        });
        popupDialog = dialog;
        popupWebView = popup;

        // Show after a short delay so an ordinary external link (intercepted above) never flashes a black screen.
        popupShowTask = () -> {
            popupShowTask = null;
            if (!isFinishing() && popupDialog == dialog && !dialog.isShowing()) {
                try { dialog.show(); } catch (Exception ignored) { }
            }
        };
        popupHandler.postDelayed(popupShowTask, 350);

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popup);
        resultMsg.sendToTarget();
        return true;
    }

    private void closePopupWindow() {
        if (popupShowTask != null) {
            popupHandler.removeCallbacks(popupShowTask);
            popupShowTask = null;
        }
        Dialog dialog = popupDialog;
        popupDialog = null;
        WebView popup = popupWebView;
        if (dialog != null && dialog.isShowing()) {
            try { dialog.dismiss(); } catch (Exception ignored) { }   // dismiss listener destroys the WebView
        } else {
            destroyPopupWebView(popup);
        }
    }

    private void destroyPopupWebView(WebView popup) {
        if (popup == null) return;
        if (popupWebView == popup) popupWebView = null;
        try {
            popup.stopLoading();
            popup.setWebChromeClient(null);
            popup.setWebViewClient(null);
            popup.destroy();
        } catch (Exception ignored) { }
    }

    private void returnToChatListFromMenu() {
        if (featureScreen) {
            exitFeatureScreen();
            return;
        }
        if (webView == null) return;
        webView.evaluateJavascript("(function(){if(window.__rkReturnToList&&window.__rkReturnToList())return 'LIST';if(window.__rkPhoneSync){window.__rkForceMode='list';window.__rkPhoneSync();return 'LIST';}return 'NONE';})()", value -> {
            String result = decodeJsString(value);
            if (!"LIST".equals(result)) {
                Toast.makeText(MainActivity.this, "Daftar chat belum siap. Coba tunggu halaman selesai dimuat.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void reloadPage() {
        if (!isOnline()) Toast.makeText(this, "Koneksi internet tidak terdeteksi.", Toast.LENGTH_SHORT).show();
        if (webView != null) webView.reload();
    }

    private boolean isOnline() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network active = manager.getActiveNetwork();
                if (active == null) return false;
                android.net.NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
                return capabilities != null && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception ignored) {
            return true;
        }
    }

    private void openOfficialSiteInBrowser() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WHATSAPP_WEB)));
        } catch (Exception e) {
            Toast.makeText(this, "Browser tidak ditemukan.", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isSecondProfile() {
        return this instanceof SecondProfileActivity;
    }

    private String profileSubtitle() {
        return isSecondProfile() ? "Akun 2 • sesi terpisah" : "Akun 1 • WhatsApp Web";
    }

    private void toggleLightMode() {
        lightMode = !lightMode;
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_LIGHT_MODE, lightMode).apply();
        if (webView != null) {
            webView.evaluateJavascript("window.__rkSetLightMode && window.__rkSetLightMode(" + lightMode + ")", null);
        }
        Toast.makeText(this, lightMode ? "Mode Ringan aktif: animasi dan efek berat dikurangi." :
                "Mode Ringan nonaktif: tampilan normal dipulihkan.", Toast.LENGTH_SHORT).show();
    }

    private void toggleCompactHeader() {
        compactHeader = !compactHeader;
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_COMPACT_HEADER, compactHeader).apply();
        applyToolbarAppearance();
        Toast.makeText(this, compactHeader ? "Judul atas disembunyikan; ikon dan menu tetap ada." :
                "Judul atas ditampilkan kembali.", Toast.LENGTH_SHORT).show();
    }

    private void applyToolbarAppearance() {
        if (toolbarRoot == null) return;
        boolean compact = compactHeader && !featureScreen;
        if (toolbarTitles != null) toolbarTitles.setVisibility(compact ? View.GONE : View.VISIBLE);
        if (toolbarAppIcon != null) {
            toolbarAppIcon.setVisibility(featureScreen ? View.GONE : View.VISIBLE);
            ViewGroup.LayoutParams iconLp = toolbarAppIcon.getLayoutParams();
            if (iconLp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) iconLp;
                lp.width = dp(compact ? 29 : 36);
                lp.height = dp(compact ? 29 : 36);
                lp.rightMargin = dp(compact ? 7 : 10);
                toolbarAppIcon.setLayoutParams(lp);
            }
        }
        toolbarRoot.setPadding(dp(compact ? 8 : 12), 0, dp(compact ? 4 : 8), 0);
        int controlHeight = featureScreen || compact ? 40 : 48;
        if (toolbarBackButton != null) {
            ViewGroup.LayoutParams backLp = toolbarBackButton.getLayoutParams();
            if (backLp instanceof LinearLayout.LayoutParams) {
                backLp.height = dp(controlHeight);
                toolbarBackButton.setLayoutParams(backLp);
            }
            toolbarBackButton.setTextSize(compact ? 30 : 34);
        }
        if (toolbarMenuButton != null) {
            ViewGroup.LayoutParams menuLp = toolbarMenuButton.getLayoutParams();
            if (menuLp instanceof LinearLayout.LayoutParams) {
                menuLp.height = dp(controlHeight);
                toolbarMenuButton.setLayoutParams(menuLp);
            }
            toolbarMenuButton.setTextSize(compact ? 25 : 27);
        }
        setToolbarHeight(featureScreen || compact ? 40 : 54);
        if (toolbarSubtitle != null && !featureScreen) toolbarSubtitle.setText(profileSubtitle());
    }

    private void switchAccountProfile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            showInfo("Akun kedua", "Akun kedua dengan sesi terpisah membutuhkan Android 9 (API 28) atau lebih baru. Android ini hanya mendukung satu penyimpanan sesi WebView dengan aman.");
            return;
        }
        Intent intent = new Intent(this, isSecondProfile() ? MainActivity.class : SecondProfileActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(intent);
        } catch (Exception e) {
            showInfo("Akun kedua", "Profil kedua tidak dapat dibuka di perangkat ini. Pastikan aplikasi dipasang ulang dari APK terbaru.");
        }
    }

    private void showMenu(View anchor) {
        Dialog dialog = new Dialog(this);
        menuDialog = dialog;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackground(makeRoundedBackground(Color.WHITE, dp(18)));
        card.setElevation(dp(8));

        TextView heading = new TextView(this);
        heading.setText(isSecondProfile() ? "WA Cloning 2" : "WA Cloning 1");
        heading.setTextColor(Color.rgb(7, 94, 84));
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(12), dp(9), dp(12), dp(2));
        card.addView(heading);
        TextView subheading = new TextView(this);
        subheading.setText("Pintasan dan pengaturan aplikasi");
        subheading.setTextColor(Color.rgb(95, 108, 104));
        subheading.setTextSize(12);
        subheading.setPadding(dp(12), 0, dp(12), dp(8));
        card.addView(subheading);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        addMenuSection(options, "TAMPILAN", new int[]{13, 18, 1, 8, 4});
        addMenuSection(options, "AKUN", new int[]{14});
        addMenuSection(options, "MEDIA & CHAT", new int[]{2, 11, 3});
        addMenuSection(options, "BAGIAN WHATSAPP", new int[]{15, 16, 17});
        addMenuSection(options, "PANGGILAN & IZIN", new int[]{9, 10});
        addMenuSection(options, "BANTUAN", new int[]{6, 5, 7, 12});
        scroll.addView(options, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int screenHeightDp = getResources().getConfiguration().screenHeightDp;
        int menuScrollHeightDp = Math.max(220, Math.min(480, screenHeightDp - 180));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(menuScrollHeightDp));
        scrollLp.topMargin = dp(2);
        card.addView(scroll, scrollLp);

        TextView close = new TextView(this);
        close.setText("Tutup");
        close.setTextColor(Color.rgb(7, 94, 84));
        close.setTextSize(14);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(12), dp(12), dp(12), dp(8));
        close.setBackground(makeRipple(Color.TRANSPARENT, dp(12)));
        close.setOnClickListener(v -> { menuDialog = null; dialog.dismiss(); });
        card.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        dialog.setOnDismissListener(d -> { if (menuDialog == dialog) menuDialog = null; });
        if (window != null) {
            int maxWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
            int maxHeight = getResources().getDisplayMetrics().heightPixels - dp(64);
            window.setLayout(Math.min(dp(380), maxWidth), Math.min(dp(650), maxHeight));
            window.setGravity(Gravity.CENTER);
        }
    }

    private GradientDrawable makeRoundedBackground(int color, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private void addMenuSection(LinearLayout parent, String title, int[] ids) {
        TextView section = new TextView(this);
        section.setText(title);
        section.setTextColor(Color.rgb(7, 94, 84));
        section.setTextSize(10);
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        section.setLetterSpacing(0.06f);
        section.setPadding(dp(12), dp(10), dp(12), dp(3));
        parent.addView(section);
        for (int id : ids) {
            String label;
            switch (id) {
                case 1: label = "Kembali ke daftar chat"; break;
                case 2: label = "Unduh foto, video, atau Status"; break;
                case 3: label = "Muat ulang WhatsApp Web"; break;
                case 4: label = desktopMode ? "Coba user-agent seluler" : "Gunakan mode kompatibilitas desktop"; break;
                case 5: label = "Buka WhatsApp Web di browser"; break;
                case 6: label = "Cara menautkan akun"; break;
                case 7: label = "Tentang & batasan unduhan"; break;
                case 8: label = "Layar fitur desktop (miring)"; break;
                case 9: label = "Cek dukungan panggilan"; break;
                case 10: label = "Izinkan mikrofon & kamera"; break;
                case 11: label = "Ekspor chat (teks, HTML, media)"; break;
                case 12: label = "Salin info layar (diagnostik)"; break;
                case 13: label = compactHeader ? "Tampilkan judul atas" : "Sembunyikan judul atas — layar lebih luas"; break;
                case 14: label = isSecondProfile() ? "Pindah ke WA Cloning 1 (akun lain)" : "Pindah ke WA Cloning 2 (akun terpisah)"; break;
                case 15: label = "Alat bisnis"; break;
                case 16: label = "Iklan"; break;
                case 17: label = "Setelan WhatsApp"; break;
                case 18: label = lightMode ? "Matikan Mode Ringan" : "Aktifkan Mode Ringan (hemat efek/CPU)"; break;
                default: label = "Pilihan";
            }
            TextView row = new TextView(this);
            row.setText(label);
            row.setTextColor(Color.rgb(35, 48, 43));
            row.setTextSize(13);
            row.setMaxLines(2);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(4), dp(10), dp(4));
            row.setBackground(makeRipple(Color.TRANSPARENT, dp(8)));
            parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)));
            row.setOnClickListener(v -> {
                Dialog activeMenu = menuDialog;
                menuDialog = null;
                if (activeMenu != null) activeMenu.dismiss();
                performMenuAction(id);
            });
        }
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(232, 237, 235));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerLp.leftMargin = dp(10);
        dividerLp.rightMargin = dp(10);
        dividerLp.topMargin = dp(2);
        parent.addView(divider, dividerLp);
    }

    private void performMenuAction(int id) {
        switch (id) {
            case 1:
                returnToChatListFromMenu();
                return;
            case 2:
                openDownloadCenter();
                return;
            case 3:
                reloadPage();
                return;
            case 4:
                toggleDesktopMode();
                return;
            case 5:
                openOfficialSiteInBrowser();
                return;
            case 6:
                showInfo("Cara menautkan akun",
                        "1. Buka WhatsApp utama di ponsel.\n2. Pilih Perangkat tertaut → Tautkan perangkat.\n3. Pindai QR yang tampil di WA Cloning, atau gunakan metode nomor telepon jika tersedia pada WhatsApp Web.\n\nJangan bagikan QR atau kode tautan kepada siapa pun.");
                return;
            case 7:
                showInfo("Tentang WA Cloning",
                        "WA Cloning RK versi 2.3.0 adalah pembungkus pihak ketiga untuk situs resmi web.whatsapp.com, bukan aplikasi resmi WhatsApp. Cookie dan penyimpanan web tidak dihapus saat aplikasi ditutup. WhatsApp tetap dapat meminta tautan ulang atau memutuskan sesi.\n\nVersi 2.3: layar bagian dan info profil diperlebar, navigasi kembali bertingkat, chat dari Arsip bisa kembali ke Arsip, pemilih foto/video memakai mekanisme WebView standar, serta Mode Ringan. Versi 2.2: perbaikan layar abu-abu dan panel tab yang menyempit, mode judul atas ringkas, serta dua profil WebView terpisah pada Android 9+. Versi 2.1: profil dan info kontak layar penuh, chat arsip, dan ekspor chat (teks + HTML, dengan atau tanpa media).\n\nUnduh media bekerja untuk tautan langsung dan sebagian media yang dapat dibaca WebView. Media sekali lihat atau konten yang dibatasi WhatsApp tidak disimpan. Struktur WhatsApp Web dapat berubah, jadi semua perangkat tetap perlu pengujian nyata.");
                return;
            case 8:
                enterFeatureScreen();
                return;
            case 9:
                showCallDiagnostics();
                return;
            case 10:
                requestCallPermissions();
                return;
            case 11:
                showExportChoice();
                return;
            case 12:
                showLayoutDiagnostics();
                return;
            case 13:
                toggleCompactHeader();
                return;
            case 14:
                switchAccountProfile();
                return;
            case 15:
                navigateWhatsAppSection("business", "Alat bisnis");
                return;
            case 16:
                navigateWhatsAppSection("advertise", "Iklan");
                return;
            case 17:
                navigateWhatsAppSection("settings", "Setelan WhatsApp");
                return;
            case 18:
                toggleLightMode();
                return;
            default:
                return;
        }
    }


    /* ---------- v2.1: ekspor chat (teks + HTML, dengan atau tanpa media) ---------- */

    private void showExportChoice() {
        if (webView == null || !isWhatsAppHost(webView.getUrl())) {
            showInfo("Ekspor chat", "Buka WhatsApp Web dan pilih chat yang ingin diekspor, lalu pilih menu ini lagi.");
            return;
        }
        String[] options = {"Tanpa media (teks dan HTML)", "Sertakan media (foto, video, dan suara)"};
        new AlertDialog.Builder(this)
                .setTitle("Ekspor chat ini")
                .setItems(options, (dialog, which) -> startChatExport(which == 1))
                .setNegativeButton("Batal", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void startChatExport(boolean withMedia) {
        if (webView == null) return;
        if (exporting) {
            Toast.makeText(this, "Ekspor lain sedang berjalan.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExportWithMedia = withMedia;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_EXPORT_PERMISSION);
            return;
        }
        File dir = new File(getCacheDir(), "export-" + System.currentTimeMillis());
        if (!dir.mkdirs()) {
            showInfo("Ekspor chat", "Penyimpanan sementara tidak bisa dibuat. Coba lagi.");
            return;
        }
        exporting = true;
        exportDir = dir;
        showExportProgress();
        evaluateAsset(webView, "export-chat.js");
        webView.evaluateJavascript("(function(){return window.__rkStartExport ? window.__rkStartExport(" + withMedia + ") : 'NOSCRIPT';})()", value -> {
            String result = decodeJsString(value);
            if (!"OK".equals(result)) {
                finishExportUi(null, "Ekspor belum bisa dimulai (" + result + "). Muat ulang halaman lalu coba lagi.");
            }
        });
    }

    private void showExportProgress() {
        exportText = new TextView(this);
        exportText.setText("Menyiapkan ekspor…\nJangan keluar dari WA Cloning sampai selesai.");
        exportText.setTextColor(Color.rgb(35, 48, 43));
        exportText.setTextSize(14);
        exportText.setPadding(dp(22), dp(14), dp(22), dp(14));
        exportDialog = new AlertDialog.Builder(this)
                .setTitle("Mengekspor chat")
                .setView(exportText)
                .setCancelable(false)
                .create();
        exportDialog.show();
    }

    /** Ends the export: closes the progress dialog, shows the result, and deletes the private temp folder. */
    private void finishExportUi(final String successMessage, final String errorMessage) {
        File dir = exportDir;
        exportDir = null;
        if (dir != null) new Thread(() -> deleteRecursively(dir)).start();
        runOnUiThread(() -> {
            exporting = false;
            if (exportDialog != null && exportDialog.isShowing()) exportDialog.dismiss();
            exportDialog = null;
            exportText = null;
            if (errorMessage != null) showInfo("Ekspor chat gagal", errorMessage);
            else if (successMessage != null) showInfo("Ekspor selesai", successMessage);
        });
    }

    /** Receives the files written by export-chat.js. Calls come from the WebView's background thread. */
    private final class ExportBridge {
        @JavascriptInterface
        public synchronized boolean append(String relativePath, String base64, boolean truncate) {
            File dir = exportDir;
            if (dir == null || relativePath == null || relativePath.contains("..") || relativePath.startsWith("/")) return false;
            try {
                File target = new File(dir, relativePath);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
                byte[] bytes = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
                try (FileOutputStream out = new FileOutputStream(target, !truncate)) {
                    out.write(bytes);
                }
                return true;
            } catch (IOException | IllegalArgumentException e) {
                return false;
            }
        }

        @JavascriptInterface
        public void progress(String text) {
            final String message = text == null ? "" : text;
            runOnUiThread(() -> { if (exportText != null) exportText.setText(message); });
        }

        @JavascriptInterface
        public String finish(String title) {
            File dir = exportDir;
            if (dir == null) return "Data ekspor sudah hilang. Coba ulangi ekspor.";
            try {
                String savedName = saveExportZip(dir, title);
                finishExportUi("Chat tersimpan sebagai \"" + savedName + "\" di folder Download/WA Cloning.", null);
                return "OK";
            } catch (IOException e) {
                return "Gagal menyimpan ZIP: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void fail(String message) {
            finishExportUi(null, message == null || message.isEmpty() ? "Ekspor tidak berhasil." : message);
        }
    }

    /** Zips the export folder (_chat.txt, chat.html, media/) into Download/WA Cloning and returns the file name. */
    private String saveExportZip(File dir, String title) throws IOException {
        String safeTitle = sanitizeFilename(title == null || title.trim().isEmpty() ? "WhatsApp" : title.trim());
        String desired = "Chat - " + safeTitle + ".zip";
        OutputStream out;
        Uri uri = null;
        String shownName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, desired);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WA Cloning");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("folder Download tidak tersedia");
            out = getContentResolver().openOutputStream(uri, "w");
            if (out == null) throw new IOException("file Download tidak bisa dibuka");
            shownName = desired;
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WA Cloning");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("folder Download tidak tersedia");
            File file = uniqueFile(directory, desired);
            out = new FileOutputStream(file);
            shownName = file.getName();
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out))) {
            addFolderToZip(zip, dir, "");
        }
        if (uri != null) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        }
        return shownName;
    }

    private void addFolderToZip(ZipOutputStream zip, File folder, String prefix) throws IOException {
        File[] children = folder.listFiles();
        if (children == null) return;
        byte[] buffer = new byte[64 * 1024];
        for (File child : children) {
            String entryName = prefix + child.getName();
            if (child.isDirectory()) {
                addFolderToZip(zip, child, entryName + "/");
                continue;
            }
            zip.putNextEntry(new ZipEntry(entryName));
            try (InputStream in = new FileInputStream(child)) {
                int read;
                while ((read = in.read(buffer)) != -1) zip.write(buffer, 0, read);
            }
            zip.closeEntry();
        }
    }

    private void deleteRecursively(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private void showLayoutDiagnostics() {
        if (webView == null) return;
        webView.evaluateJavascript("(function(){return window.__rkLayoutDebug?window.__rkLayoutDebug():'Skrip belum dimuat. Muat ulang halaman.';})()", value -> {
            if (isFinishing()) return;
            final String report = "Aplikasi 2.3.0 | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") | "
                    + Build.MANUFACTURER + " " + Build.MODEL + "\n" + decodeJsString(value);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Info layar (diagnostik)")
                    .setMessage(report)
                    .setNegativeButton("Tutup", (d, w) -> d.dismiss())
                    .setPositiveButton("Salin", (d, w) -> {
                        try {
                            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("info-layar", report));
                                Toast.makeText(MainActivity.this, "Laporan disalin.", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception ignored) { }
                    })
                    .show();
        });
    }

    /* ---------- v2.0: feature screen, call readiness, diagnostics ---------- */

    private void setToolbarHeight(int heightDp) {
        if (toolbarRoot == null) return;
        ViewGroup.LayoutParams lp = toolbarRoot.getLayoutParams();
        if (lp == null) return;
        lp.height = dp(heightDp);
        toolbarRoot.setLayoutParams(lp);
    }

    /** Opens WhatsApp's real desktop layout on its own screen so the sidebar and header call buttons work like on a laptop. */
    private void enterFeatureScreen() {
        if (webView == null || featureScreen) return;
        if ("login".equals(currentScreenMode)) {
            Toast.makeText(this, "Tautkan akun dulu, setelah itu Layar fitur bisa dipakai.", Toast.LENGTH_SHORT).show();
            return;
        }
        featureScreen = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (bottomNavScroll != null) bottomNavScroll.setVisibility(View.GONE);
        if (toolbarBackButton != null) toolbarBackButton.setVisibility(View.VISIBLE);
        if (toolbarSubtitle != null) toolbarSubtitle.setText("Layar fitur • tampilan desktop");
        applyToolbarAppearance();
        WebSettings settings = webView.getSettings();
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        webView.postDelayed(this::applyDesktopFeatureJs, 350);
        Toast.makeText(this, "Layar fitur: cubit untuk memperbesar. Tombol Kembali pulang ke tampilan HP.", Toast.LENGTH_LONG).show();
    }

    private void applyDesktopFeatureJs() {
        if (webView == null || !featureScreen) return;
        webView.evaluateJavascript("(function(){return window.__rkEnterDesktop?(window.__rkEnterDesktop()?'OK':'FAIL'):'NOSCRIPT';})()", value -> {
            if (!featureScreen) return;
            if (!"OK".equals(decodeJsString(value))) {
                Toast.makeText(MainActivity.this, "Layar fitur belum siap. Tunggu halaman selesai dimuat, lalu coba lagi.", Toast.LENGTH_LONG).show();
                exitFeatureScreen();
            }
        });
    }

    private void exitFeatureScreen() {
        if (!featureScreen) return;
        featureScreen = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (toolbarBackButton != null) toolbarBackButton.setVisibility(View.GONE);
        if (toolbarSubtitle != null) toolbarSubtitle.setText(profileSubtitle());
        applyToolbarAppearance();
        if (bottomNavScroll != null) bottomNavScroll.setVisibility(View.VISIBLE);
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        // The script notifies the app of the resulting screen (list/chat), which updates the toolbar and bottom bar.
        webView.postDelayed(() -> {
            if (webView != null) {
                webView.evaluateJavascript("(function(){return window.__rkExitDesktop?(window.__rkExitDesktop()?'OK':'FAIL'):'NOSCRIPT';})()", null);
            }
        }, 250);
    }

    private boolean hasCallPermissions() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCallPermissions() {
        if (hasCallPermissions()) {
            Toast.makeText(this, "Izin mikrofon dan kamera sudah aktif.", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, REQUEST_CALL_PERMISSIONS);
    }

    /** Asks once, after the account is linked, so a call never has to wait for the permission dialog. */
    private void maybeAskCallPermissions(String mode) {
        if (mode == null || "login".equals(mode) || isFinishing()) return;
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.getBoolean(PREF_CALL_PERMISSION_ASKED, false) || hasCallPermissions()) return;
        prefs.edit().putBoolean(PREF_CALL_PERMISSION_ASKED, true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Siapkan panggilan suara & video")
                .setMessage("Agar telepon dan video call WhatsApp bisa dipakai, izinkan akses mikrofon dan kamera. Izin hanya dipakai saat panggilan.")
                .setNegativeButton("Nanti", (d, w) -> d.dismiss())
                .setPositiveButton("Izinkan", (d, w) -> requestCallPermissions())
                .show();
    }

    private String buildNativeCallReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("Aplikasi 2.3.0 | Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") | ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
                if (info != null) sb.append("WebView: ").append(info.packageName).append(' ').append(info.versionName).append('\n');
            }
        } catch (Exception ignored) { }
        sb.append("Izin Android: mikrofon=")
                .append(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "ya" : "BELUM")
                .append(" kamera=")
                .append(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? "ya" : "BELUM")
                .append('\n');
        sb.append("Mode kompatibilitas desktop: ").append(desktopMode ? "aktif" : "mati")
                .append(" | layar fitur: ").append(featureScreen ? "terbuka" : "tutup").append('\n');
        return sb.toString();
    }

    /** Shows why WhatsApp enables or disables the call buttons inside this WebView. Copyable, to send to the developer. */
    private void showCallDiagnostics() {
        if (webView == null) return;
        webView.evaluateJavascript("(function(){return window.__rkCallDiagStart?window.__rkCallDiagStart():'NOSCRIPT';})()", started -> {
            if (webView == null) return;
            webView.postDelayed(() -> {
                if (webView == null || isFinishing()) return;
                webView.evaluateJavascript("(function(){return window.__rkCallDiagReport?window.__rkCallDiagReport():'Skrip belum dimuat. Muat ulang halaman.';})()", value -> {
                    if (isFinishing()) return;
                    final String report = buildNativeCallReport() + decodeJsString(value);
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Cek dukungan panggilan")
                            .setMessage(report)
                            .setNegativeButton("Tutup", (d, w) -> d.dismiss())
                            .setPositiveButton("Salin", (d, w) -> {
                                try {
                                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (clipboard != null) {
                                        clipboard.setPrimaryClip(ClipData.newPlainText("laporan-panggilan", report));
                                        Toast.makeText(MainActivity.this, "Laporan disalin.", Toast.LENGTH_SHORT).show();
                                    }
                                } catch (Exception ignored) { }
                            })
                            .show();
                });
            }, 900);
        });
    }

    private void evaluateAsset(WebView target, String assetName) {
        if (target == null) return;
        try (InputStream input = getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            target.evaluateJavascript(new String(output.toByteArray(), StandardCharsets.UTF_8), null);
        } catch (IOException ignored) { }
    }

    /** Makes navigator.platform / userAgentData agree with the desktop user-agent string (only in desktop-compat mode). */
    private void injectDesktopCompat(WebView target) {
        if (!desktopMode) return;
        evaluateAsset(target, "desktop-compat.js");
    }

    private void showInfo(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Mengerti", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_PICKER) {
            ValueCallback<Uri[]> callback = fileChooserCallback;
            fileChooserCallback = null;
            if (callback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    try { results = WebChromeClient.FileChooserParams.parseResult(resultCode, data); }
                    catch (Exception ignored) { }
                    if (results == null || results.length == 0) {
                        Uri uri = data.getData();
                        if (uri != null) results = new Uri[]{uri};
                        else if (data.getClipData() != null) {
                            int count = data.getClipData().getItemCount();
                            results = new Uri[count];
                            for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                try { callback.onReceiveValue(results); } catch (Exception ignored) { }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;

        if (requestCode == REQUEST_WEB_PERMISSIONS) {
            if (pendingWebPermission != null) {
                if (granted) pendingWebPermission.grant(pendingWebResources);
                else pendingWebPermission.deny();
            }
            pendingWebPermission = null;
            pendingWebResources = null;
        } else if (requestCode == REQUEST_DOWNLOAD_PERMISSION) {
            PendingDownload download = pendingDownload;
            pendingDownload = null;
            if (granted && download != null) enqueueDownload(download);
            else Toast.makeText(this, "Izin penyimpanan diperlukan pada Android lama untuk mengunduh file.", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQUEST_EXPORT_PERMISSION) {
            Boolean withMedia = pendingExportWithMedia;
            pendingExportWithMedia = null;
            if (granted && withMedia != null) startChatExport(withMedia);
            else Toast.makeText(this, "Izin penyimpanan diperlukan untuk menyimpan hasil ekspor di Android lama.", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQUEST_BLOB_PERMISSION) {
            int mediaId = pendingBlobMediaId;
            String mime = pendingBlobMime;
            String name = pendingBlobName;
            pendingBlobMediaId = -1;
            if (granted && mediaId >= 0) beginBlobDownload(mediaId, mime, name);
            else Toast.makeText(this, "Izin penyimpanan diperlukan untuk menyimpan media di Android lama.", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQUEST_CALL_PERMISSIONS) {
            Toast.makeText(this, granted
                    ? "Izin mikrofon dan kamera aktif. Panggilan siap dicoba."
                    : "Tanpa izin mikrofon/kamera panggilan tidak bisa berjalan. Izinkan lewat Setelan Android → Aplikasi → WA Cloning → Izin.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            try { webView.saveState(outState); } catch (Exception ignored) { }
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            // Pausing the WebView stops its audio/video capture; do not cut a call window that is open.
            if (popupWebView == null) webView.onPause();
            CookieManager.getInstance().flush();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        CookieManager.getInstance().flush();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Do not reload on resume; this preserves the current chat screen whenever Android keeps the Activity.
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
        closePopupWindow();
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (pendingWebPermission != null) {
            try { pendingWebPermission.deny(); } catch (Exception ignored) { }
            pendingWebPermission = null;
        }
        if (blobOutputStream != null || blobProgressDialog != null) cancelBlobDownload();
        if (webView != null) {
            CookieManager.getInstance().flush();
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (keyboardVisible) {
            try {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null && webView != null) imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
            } catch (Exception ignored) { }
            return;
        }
        if (featureScreen) {
            exitFeatureScreen();
            return;
        }
        if (webView != null && !backCheckPending) {
            backCheckPending = true;
            webView.evaluateJavascript("(function(){if(window.__rkHandleBack)return window.__rkHandleBack();var r=document.querySelector('.rk-phone-root');if(r&&(r.classList.contains('rk-phone-chat')||r.classList.contains('rk-phone-panel')||r.classList.contains('rk-phone-navpanel'))){if(window.__rkReturnToList)window.__rkReturnToList();return 'HANDLED';}return 'EXIT';})()", value -> {
                backCheckPending = false;
                if (!isFinishing() && "EXIT".equals(decodeJsString(value))) promptExit();
            });
            return;
        }
        if (!backCheckPending) promptExit();
    }

    private void promptExit() {
        new AlertDialog.Builder(this)
                .setTitle("Keluar dari WA Cloning?")
                .setMessage("Sesi lokal tidak dihapus. WhatsApp tetap dapat meminta tautan ulang atau memutuskan sesi.")
                .setNegativeButton("Lanjut chat", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Keluar aplikasi", (dialog, which) -> {
                    CookieManager.getInstance().flush();
                    finish();
                })
                .show();
    }
}
