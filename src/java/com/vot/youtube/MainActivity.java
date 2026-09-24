/*
 * MainActivity — Android-клиент YouTube со встроенным voice-over-translation (VoT).
 *
 * Сборка: ручной пайплайн (aapt2 -> javac -> d8 -> zipalign -> apksigner), без AndroidX.
 * Java 8-совместимый код (без стримов, Records и т.п.).
 */
package com.vot.youtube;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PorterDuff;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://m.youtube.com/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36";

    private static final String VOT_BOOTSTRAP = "vot/bootstrap.js";
    private static final String VOT_BUNDLE = "vot/vot.user.js";

    // Автообновление: проверка последнего релиза на GitHub
    private static final String GITHUB_LATEST_API =
            "https://api.github.com/repos/Erzkanzler2k/youtube-vot/releases/latest";
    private static final String GITHUB_LATEST_HTML =
            "https://github.com/Erzkanzler2k/youtube-vot/releases/latest";
    private static final String JSDELIVR_MANIFEST =
            "https://cdn.jsdelivr.net/gh/Erzkanzler2k/youtube-vot@latest/release-manifest.json";
    // Свежий API резолва последней версии. Кэш data.jsdelivr.com на этом URL
    // может держать старый тег до 12 ч после релиза, поэтому запрос идёт с
    // cache-buster (?cb=<время>) — иначе пользователь не увидит обновление.
    private static final String JSDELIVR_RESOLVED =
            "https://data.jsdelivr.com/v1/packages/gh/Erzkanzler2k/youtube-vot/resolved?cb=";
    private static final String RELEASE_TAG_MARKER = "/releases/tag/";
    private static final String APK_ASSET_NAME = "YouTubeVot.apk";
    private static final String TAG_UPD = "YouTubeVotUpdate";
    // Скачивание APK всегда пересобираем на CDN-зеркало по тегу: github.com
    // в РФ заблокирован, а файл коммитится в репозиторий на каждый релиз.
    // ФОРМАТ: String.format(JSDELIVR_DOWNLOAD_TEMPLATE, tag)
    private static final String JSDELIVR_DOWNLOAD_TEMPLATE =
            "https://cdn.jsdelivr.net/gh/Erzkanzler2k/youtube-vot@%s/out/YouTubeVot.apk";
    private static final String PREF_SKIP_UPDATE = "skip_update_version";
    private static final String PREF_PENDING_URL = "pending_update_url";
    private static final String PREF_PENDING_TAG = "pending_update_tag";
    /** id текущей загрузки APK в DownloadManager (в prefs — для манифестного receiver'а). */
    static final String PREF_DOWNLOAD_ID = "update_download_id";
    /** Действие, которым манифестный receiver будит Activity на завершение загрузки. */
    static final String ACTION_HANDLE_DOWNLOAD = "com.vot.youtube.HANDLE_DOWNLOAD";
    static final String EXTRA_DOWNLOAD_ID = "download_id";
    /** Тег логов обновления, доступный receiver'у. */
    static final String TAG_UPD_PUBLIC = TAG_UPD;
    private static final String PREF_AUTO_UPDATE = "auto_update";
    private static final String PREF_WORKER_URL = "worker_url";
    // Метки времени последней успешной проверки и последней попытки (для ретраев без спама API)
    private static final String PREF_LAST_CHECK = "last_update_check";
    private static final String PREF_LAST_ATTEMPT = "last_update_attempt";
    private static final long UPDATE_CHECK_INTERVAL = 60 * 60 * 1000L;  // авто-проверка не чаще 1 раза в час
    private static final long UPDATE_RETRY_INTERVAL = 10 * 60 * 1000L;  // повтор при сбое не чаще 1 раза в 10 минут
    private static final int REQ_VPN_PERMISSION = 7001;                 // разрешение системного VPN (ветка bypass)

    private WebView web;
    private FrameLayout customViewContainer;
    private View topBar;
    private View bottomBar;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ProgressBar progressBar;
    private ImageView btnBack, btnForward;
    private View splashOverlay;
    private ObjectAnimator splashPulse;
    private View offlineOverlay;
    private ConnectivityManager.NetworkCallback netCallback;
    private boolean updateCheckRunning;
    private long currentDownloadId = -1L;
    /** id загрузки, для которого уже запущена обработка (защита от повтора). */
    private long handledDownloadId = -1L;
    private File downloadedApkFile;
    private DownloadManager downloadManager;
    private BroadcastReceiver downloadReceiver;

    // Базовые отступы панелей (без системных инсетов), чтобы корректно дополнять их
    private int topBarBaseStart, topBarBaseEnd, topBarBaseTop, topBarBaseBottom;
    private int bottomBarBaseStart, bottomBarBaseEnd, bottomBarBaseTop, bottomBarBaseBottom;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Тёмная системная панель в цвет YouTube; статус-бар скрыт (вид медиа-приложения)
        getWindow().setNavigationBarColor(0xFF0F0F0F);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        setNormalSystemUi();

        customViewContainer = findViewById(R.id.custom_view_container);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        setupSystemBars();
        web = findViewById(R.id.web);
        progressBar = findViewById(R.id.progress);
        splashOverlay = findViewById(R.id.splash_overlay);
        startSplashPulse();
        setupRegionPicker();
        updateRegionLabel();
        offlineOverlay = findViewById(R.id.offline_overlay);
        setupRetryButton();
        registerNetworkCallback();
        btnBack = findViewById(R.id.btn_back);
        btnForward = findViewById(R.id.btn_forward);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(USER_AGENT);

        // Тёмная страница на весь экран (как в оригинальном приложении с тёмной темой)
        if (Build.VERSION.SDK_INT >= 29) {
            if (Build.VERSION.SDK_INT >= 33) {
                s.setAlgorithmicDarkeningAllowed(true);
            }
            s.setForceDark(WebSettings.FORCE_DARK_ON);
        }

        // Тёмный фон вместо белых вспышек при навигации; без цветного свечения краёв
        web.setBackgroundColor(0xFF0F0F0F);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Чистый «апповый» вид: без системных скроллбаров
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                injectVot(view);
                updateNavState();
                hideOffline();
                view.setAlpha(0f); // плавное появление вместо резкой смены кадра
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                updateNavState();
                hideSplash();
                view.animate().alpha(1f).setDuration(220).start();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Только ошибка загрузки главного кадра (не фоновые ресурсы)
                if (request != null && request.isForMainFrame()) {
                    showOffline();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl().toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Блокировка рекламы: рекламным доменам отдаём пустой ответ
                String url = request.getUrl().toString();
                if (isAdUrl(url)) {
                    return new WebResourceResponse("text/html", "utf-8",
                            new java.io.ByteArrayInputStream(new byte[0]));
                }
                return null; // остальное — стандартно
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    progressBar.animate().cancel();
                    progressBar.setProgress(newProgress);
                    if (newProgress >= 100) {
                        // Плавное исчезание при полной загрузке
                        progressBar.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (progressBar.getProgress() >= 100) {
                                    progressBar.setVisibility(View.GONE);
                                }
                                progressBar.setAlpha(1f);
                            }
                        });
                    } else if (progressBar.getVisibility() != View.VISIBLE) {
                        // Плавное появление при старте загрузки
                        progressBar.setAlpha(0f);
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.animate().alpha(1f).setDuration(150).start();
                    }
                }
                updateNavState();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                customViewContainer.setVisibility(View.VISIBLE);
                customViewContainer.addView(customView,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                if (topBar != null) topBar.setVisibility(View.GONE);
                if (bottomBar != null) bottomBar.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                setImmersiveSystemUi();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                customViewContainer.removeView(customView);
                customView = null;
                customViewContainer.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
                if (topBar != null) topBar.setVisibility(View.VISIBLE);
                if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                setNormalSystemUi();
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype, long contentLength) {
                new SaveFileTask(MainActivity.this).execute(url, userAgent, contentDisposition, mimetype);
            }
        });

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                if (web.canGoBack()) web.goBack();
            }
        });
        btnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                if (web.canGoForward()) web.goForward();
            }
        });
        ImageView refresh = findViewById(R.id.btn_refresh);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                web.reload();
            }
        });
        ImageView exit = findViewById(R.id.btn_exit);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                finish();
            }
        });

        ImageView settings = findViewById(R.id.btn_settings);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                showSettingsDialog();
            }
        });

        String initial = HOME_URL;
        Uri data = getIntent().getData();
        if (data != null && (data.getHost() == null || data.getHost().contains("youtube") || "youtu.be".equals(data.getHost()))) {
            initial = data.toString();
        }
        web.loadUrl(withRegion(initial));

        registerDownloadReceiver();
        handleDownloadIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDownloadIntent(intent);
    }

    /** Завершение загрузки обновления, пришедшее от манифестного receiver'а. */
    private void handleDownloadIntent(Intent intent) {
        if (intent == null || !ACTION_HANDLE_DOWNLOAD.equals(intent.getAction())) return;
        long id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L);
        if (id >= 0) handleDownloadComplete(id);
    }

    /** Инжекция VoT: сначала bootstrap (GM-полифиллы + настройки), затем сам скрипт. */
    private void injectVot(WebView view) {
        if (!view.getUrl().startsWith("http")) return;
        view.evaluateJavascript(readAsset(VOT_BOOTSTRAP), null);
        view.evaluateJavascript(readAsset(VOT_BUNDLE), null);
    }

    private String readAsset(String path) {
        try {
            AssetManager am = getAssets();
            InputStream is = am.open(path);
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder((int) is.available() + 4096);
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean handleNavigation(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // Вход / аккаунт — отключены в этой сборке
            if (isSignInUrl(url)) {
                showBlockedToast();
                web.loadUrl(HOME_URL);
                return true;
            }
            // Переходы по рекламным ссылкам — глушим
            if (isAdUrl(url)) return true;

            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return false;
            if (isYoutubeHost(host)) return false; // загружаем внутри WebView
            openExternal(url);
            return true;
        }
        // intent:/tel:/mailto:/market: и т.п.
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
        }
        return true;
    }

    private static boolean isYoutubeHost(String host) {
        return host.contains("youtube.com") || host.contains("youtu.be")
                || host.contains("youtube-nocookie.com") || host.contains("googlevideo.com")
                || host.contains("google.com") || host.contains("ggpht.com")
                || host.contains("ytimg.com") || host.contains("youtube.googleapis.com");
    }

    /** Реклама: рекламные домены и пути, которым отдаём пустой ответ. */
    private static boolean isAdUrl(String url) {
        String u = url.toLowerCase();
        try {
            Uri uri = Uri.parse(u);
            String host = uri.getHost() == null ? "" : uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
            return host.endsWith("doubleclick.net")
                    || host.endsWith("googlesyndication.com")
                    || host.endsWith("googletagservices.com")
                    || host.endsWith("googleadservices.com")
                    || host.endsWith("google-analytics.com")
                    || host.endsWith("googletagmanager.com")
                    || host.endsWith("scorecardresearch.com")
                    || host.endsWith("moatads.com")
                    || host.endsWith("criteo.com")
                    || host.endsWith("outbrain.com")
                    || host.endsWith("adservice.google.com")
                    || host.equals("ads.youtube.com")
                    || path.contains("/pagead/")
                    || path.contains("/pagead2/")
                    || u.contains("youtube.com/api/stats/ads");
        } catch (Exception e) {
            return false;
        }
    }

    /** Страницы входа / аккаунта — заблокированы. */
    private static boolean isSignInUrl(String url) {
        String u = url.toLowerCase();
        return u.contains("accounts.google.")
                || u.contains("consent.google.")
                || u.contains("google.com/accounts/")
                || u.contains("google.com/signin")
                || u.contains("google.com/login")
                || u.contains("youtube.com/signin")
                || u.contains("youtube.com/account")
                || u.contains("youtube.com/consent");
    }

    private void showBlockedToast() {
        Toast.makeText(this, "Вход отключён в этой сборке", Toast.LENGTH_SHORT).show();
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------------- Безопасные зоны (вырез камеры, статус-бар, жесты) ---------------- */

    /** Слушаем системные инсеты и отодвигаем панели от выреза камеры и жестовой зоны. */
    private void setupSystemBars() {
        if (topBar == null || bottomBar == null) return;
        topBarBaseStart = topBar.getPaddingStart();
        topBarBaseEnd = topBar.getPaddingEnd();
        topBarBaseTop = topBar.getPaddingTop();
        topBarBaseBottom = topBar.getPaddingBottom();
        bottomBarBaseStart = bottomBar.getPaddingStart();
        bottomBarBaseEnd = bottomBar.getPaddingEnd();
        bottomBarBaseTop = bottomBar.getPaddingTop();
        bottomBarBaseBottom = bottomBar.getPaddingBottom();
        final View root = findViewById(R.id.root_container);
        if (root == null) return;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                applySafeInsets(insets);
                return insets;
            }
        });
    }

    /**
     * Считает безопасные отступы (статус-бар, навигация, вырез камеры)
     * и применяет их к панелям: верх — под камеру/статус-бар, низ — над жестами.
     */
    private void applySafeInsets(WindowInsets insets) {
        if (topBar == null || bottomBar == null) return;
        int left = 0, top = 0, right = 0, bottom = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets si = insets.getInsets(WindowInsets.Type.systemBars());
            left = si.left;
            top = si.top;
            right = si.right;
            bottom = si.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
        }
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                top = Math.max(top, cutout.getSafeInsetTop());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                left = Math.max(left, cutout.getSafeInsetLeft());
                right = Math.max(right, cutout.getSafeInsetRight());
            }
        }
        topBar.setPaddingRelative(topBarBaseStart + left, topBarBaseTop + top,
                topBarBaseEnd + right, topBarBaseBottom);
        bottomBar.setPaddingRelative(bottomBarBaseStart + left, bottomBarBaseTop,
                bottomBarBaseEnd + right, bottomBarBaseBottom + bottom);
        View root = findViewById(R.id.root_container);
        if (root != null) root.setPadding(left, 0, right, 0);
    }

    /** Обычный режим: статус-бар скрыт, навигационная панель видна (в цвет фона). */
    private void setNormalSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** Полноэкранное видео: скрываем всё системное UI (immerse-sticky). */
    private void setImmersiveSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** Назад/вперёд доступны только когда есть история. */
    private void updateNavState() {
        if (btnBack == null || btnForward == null) return;
        boolean back = web.canGoBack();
        boolean forward = web.canGoForward();
        btnBack.setEnabled(back);
        btnBack.setAlpha(back ? 1f : 0.35f);
        btnForward.setEnabled(forward);
        btnForward.setAlpha(forward ? 1f : 0.35f);
    }

    /** Лёгкая вибрация + микро-пульс иконки при нажатии. */
    private void pressFeedback(final View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        v.animate().scaleX(0.82f).scaleY(0.82f).setDuration(70)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
                    }
                });
    }

    /** Пульс иконки на загрузочном экране (бесконечная анимация прозрачности). */
    private void startSplashPulse() {
        if (splashOverlay == null) return;
        ImageView icon = findViewById(R.id.splash_icon);
        if (splashPulse == null) {
            splashPulse = ObjectAnimator.ofFloat(icon, "alpha", 1f, 0.35f);
            splashPulse.setDuration(700);
            splashPulse.setRepeatCount(ValueAnimator.INFINITE);
            splashPulse.setRepeatMode(ValueAnimator.REVERSE);
            splashPulse.start();
        }
    }

    /* ---------------- Страна рекомендуемого контента (регион YouTube) ---------------- */

    private SharedPreferences regionPrefs() {
        return getSharedPreferences("vot_prefs", MODE_PRIVATE);
    }

    private String regionCode() {
        return regionPrefs().getString("region", "auto");
    }

    private String deviceCode() {
        Locale l = Locale.getDefault();
        String c = l.getCountry().toLowerCase(Locale.US);
        return c.isEmpty() ? "us" : c;
    }

    private String effectiveCode() {
        String stored = regionCode();
        return "auto".equals(stored) ? deviceCode() : stored;
    }

    /** Добавляет hl/gl-параметры к URL, сохраняя существующий query. */
    private String withRegion(String url) {
        String gl = effectiveCode();
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "hl=ru&gl=" + gl;
    }

    private int indexOfCode(String[] codes, String code) {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(code)) return i;
        }
        return -1;
    }

    private void setupRegionPicker() {
        View row = findViewById(R.id.splash_region);
        if (row == null) return;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                showRegionDialog();
            }
        });
    }

    private void showRegionDialog() {
        final String[] labels = getResources().getStringArray(R.array.region_labels);
        final String[] codes = getResources().getStringArray(R.array.region_codes);
        int checked = indexOfCode(codes, regionCode());
        if (checked < 0) checked = 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.region_title)
                .setSingleChoiceItems(labels, checked, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int sel = ((AlertDialog) d).getListView().getCheckedItemPosition();
                        applyRegion(sel);
                    }
                })
                .show();
    }

    private void applyRegion(int index) {
        String[] codes = getResources().getStringArray(R.array.region_codes);
        if (index < 0 || index >= codes.length) return;
        regionPrefs().edit().putString("region", codes[index]).apply();
        updateRegionLabel();
        web.loadUrl(withRegion(HOME_URL));
    }

    private String regionLabel() {
        String[] labels = getResources().getStringArray(R.array.region_labels);
        String[] codes = getResources().getStringArray(R.array.region_codes);
        String eff = effectiveCode();
        int idx = indexOfCode(codes, eff);
        String name = idx >= 0 ? labels[idx] : eff.toUpperCase(Locale.US);
        StringBuilder sb = new StringBuilder(getString(R.string.region_label_prefix)).append(name);
        if ("auto".equals(regionCode())) sb.append(getString(R.string.region_by_device));
        return sb.toString();
    }

    private void updateRegionLabel() {
        TextView label = findViewById(R.id.splash_region_label);
        if (label == null) return;
        label.setText(regionLabel());
    }

    /* ---------------- Настройки приложения ---------------- */

    private void showSettingsDialog() {
        SharedPreferences prefs = regionPrefs();
        final AlertDialog[] holder = new AlertDialog[1];

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // Страна контента
        LinearLayout rowRegion = settingsRow(R.drawable.ic_globe,
                getString(R.string.settings_region), regionLabel());
        rowRegion.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                if (holder[0] != null) holder[0].dismiss();
                showRegionDialog();
            }
        });
        content.addView(rowRegion);

        // Автообновление
        LinearLayout rowUpdate = settingsRow(R.drawable.ic_refresh,
                getString(R.string.settings_auto_update),
                getString(R.string.settings_auto_update_desc));
        rowUpdate.setClickable(false);
        rowUpdate.setFocusable(false);
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(PREF_AUTO_UPDATE, true));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                regionPrefs().edit().putBoolean(PREF_AUTO_UPDATE, checked).apply();
            }
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.setMarginStart(dp(16));
        rowUpdate.addView(sw, swLp);
        content.addView(rowUpdate);

        // Проверить обновления сейчас
        LinearLayout rowCheck = settingsRow(R.drawable.ic_refresh,
                getString(R.string.settings_check_update),
                getString(R.string.settings_check_update_desc));
        rowCheck.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                if (holder[0] != null) holder[0].dismiss();
                checkForUpdate(true);
            }
        });
        content.addView(rowCheck);

        // Обход блокировок РКН (VPN) — ветка bypass
        LinearLayout rowBypass = settingsRow(R.drawable.ic_settings,
                getString(R.string.settings_bypass),
                getString(R.string.settings_bypass_desc));
        rowBypass.setClickable(false);
        rowBypass.setFocusable(false);
        final SharedPreferences bypassPrefs =
                getSharedPreferences(BypassVpnService.PREFS_BYPASS, MODE_PRIVATE);
        Switch swBypass = new Switch(this);
        swBypass.setChecked(bypassPrefs.getBoolean(BypassVpnService.PREF_ENABLED, false));
        swBypass.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked) {
                    enableBypass();
                } else {
                    Intent stop = new Intent(MainActivity.this, BypassVpnService.class)
                            .setAction(BypassVpnService.ACTION_STOP);
                    // startService, а не stopService: у stopService в состоянии
                    // «fg-сервис с startRequested=false» (после системного рестарта)
                    // вызов является no-op, и VPN не снимается. ACTION_STOP через
                    // startService гарантированно доставляется в onStartCommand.
                    startService(stop);
                    bypassPrefs.edit().putBoolean(BypassVpnService.PREF_ENABLED, false).apply();
                }
            }
        });
        LinearLayout.LayoutParams swBypassLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swBypassLp.setMarginStart(dp(16));
        rowBypass.addView(swBypass, swBypassLp);
        content.addView(rowBypass);

        // Сервер перевода
        String worker = prefs.getString(PREF_WORKER_URL, "");
        String workerSub = worker.isEmpty() ? getString(R.string.settings_default_worker) : worker;
        LinearLayout rowWorker = settingsRow(R.drawable.ic_settings,
                getString(R.string.settings_worker), workerSub);
        rowWorker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                if (holder[0] != null) holder[0].dismiss();
                showWorkerDialog();
            }
        });
        content.addView(rowWorker);

        // Сброс настроек
        LinearLayout rowReset = settingsRow(R.drawable.ic_close,
                getString(R.string.settings_reset), null);
        rowReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                if (holder[0] != null) holder[0].dismiss();
                confirmReset();
            }
        });
        content.addView(rowReset);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        holder[0] = dialog;
        dialog.show();
    }

    /* ---------------- Обход блокировок (VPN, ветка bypass) ---------------- */

    private void enableBypass() {
        if (!BypassVpnService.isReady()) {
            Toast.makeText(this, R.string.bypass_not_ready, Toast.LENGTH_LONG).show();
            getSharedPreferences(BypassVpnService.PREFS_BYPASS, MODE_PRIVATE)
                    .edit().putBoolean(BypassVpnService.PREF_ENABLED, false).apply();
            return;
        }
        // Системный диалог разрешения VPN (VpnService.prepare). Если уже есть
        // чужой активный VPN — Android предложит его отключить.
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            try {
                startActivityForResult(prepare, REQ_VPN_PERMISSION);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.bypass_not_ready, Toast.LENGTH_LONG).show();
            }
        } else {
            startBypassService();
        }
    }

    private void startBypassService() {
        Intent i = new Intent(this, BypassVpnService.class)
                .setAction(BypassVpnService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i); // сервис сразу вызывает startForeground
        } else {
            startService(i);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN_PERMISSION && resultCode == RESULT_OK) {
            // Первый prepare() мог только снять чужой VPN — проверяем ещё раз и стартуем.
            if (VpnService.prepare(this) == null) {
                startBypassService();
            } else {
                getSharedPreferences(BypassVpnService.PREFS_BYPASS, MODE_PRIVATE)
                        .edit().putBoolean(BypassVpnService.PREF_ENABLED, false).apply();
            }
        }
    }

    /** Строка диалога настроек: иконка + заголовок + подпись. */
    private LinearLayout settingsRow(int iconRes, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        int pad = dp(16);
        row.setPadding(pad, dp(12), pad, dp(12));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFF9AA0A6, PorterDuff.Mode.SRC_IN);
        int iconSize = dp(22);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(dp(16));
        icon.setLayoutParams(iconLp);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColor(R.color.text_primary));
        titleView.setTextSize(15);
        texts.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = new TextView(this);
            subView.setText(subtitle);
            subView.setTextColor(getColor(R.color.text_secondary));
            subView.setTextSize(12);
            subView.setSingleLine(true);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(2);
            subView.setLayoutParams(subLp);
            texts.addView(subView);
        }
        row.addView(texts);
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showWorkerDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.settings_worker_hint);
        String current = regionPrefs().getString(PREF_WORKER_URL, "");
        if (!current.isEmpty()) {
            input.setText(current);
            input.setSelection(input.getText().length());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_worker_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        applyWorkerUrl(input.getText().toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Сохраняем адрес воркера и применяем его на странице (ключ VoT в localStorage). */
    private void applyWorkerUrl(String value) {
        final String clean = (value == null ? "" : value).trim();
        SharedPreferences.Editor ed = regionPrefs().edit();
        if (clean.isEmpty()) ed.remove(PREF_WORKER_URL);
        else ed.putString(PREF_WORKER_URL, clean);
        ed.apply();
        if (web != null) {
            String js = "(function(){try{var ls=window.localStorage;" +
                    (clean.isEmpty()
                            ? "ls.removeItem('proxyWorkerHost');"
                            : "ls.setItem('proxyWorkerHost'," + JSONObject.quote(clean) + ");") +
                    "}catch(e){}})();";
            web.evaluateJavascript(js, null);
            web.reload();
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_reset)
                .setMessage(R.string.settings_reset_confirm)
                .setPositiveButton(R.string.reset_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        resetPreferences();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void resetPreferences() {
        regionPrefs().edit().clear().apply();
        if (web != null) {
            web.evaluateJavascript(
                    "(function(){try{window.localStorage.removeItem('proxyWorkerHost');}catch(e){}})();", null);
            web.loadUrl(withRegion(HOME_URL));
        }
        Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show();
    }

    /* ---------------- Офлайн-экран ---------------- */

    private void setupRetryButton() {
        View retry = findViewById(R.id.btn_retry);
        if (retry == null) return;
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pressFeedback(v);
                String url = web.getUrl();
                if (url == null || !url.startsWith("http")) url = withRegion(HOME_URL);
                web.loadUrl(url);
            }
        });
    }

    private void showOffline() {
        if (offlineOverlay == null) return;
        offlineOverlay.setVisibility(View.VISIBLE);
        offlineOverlay.setAlpha(0f);
        offlineOverlay.animate().alpha(1f).setDuration(200).start();
    }

    private void hideOffline() {
        if (offlineOverlay == null || offlineOverlay.getVisibility() != View.VISIBLE) return;
        offlineOverlay.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                offlineOverlay.setVisibility(View.GONE);
                offlineOverlay.setAlpha(1f);
            }
        });
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (offlineOverlay != null
                                    && offlineOverlay.getVisibility() == View.VISIBLE) {
                                String url = web.getUrl();
                                if (url == null || !url.startsWith("http")) url = withRegion(HOME_URL);
                                web.loadUrl(url);
                            }
                        }
                    });
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
    }

    private void unregisterNetworkCallback() {
        if (netCallback == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        netCallback = null;
    }

    /* ---------------- Автообновление с GitHub ---------------- */

    private void checkForUpdate(boolean manual) {
        if (web == null || isFinishing() || isDestroyed()) return;
        if (updateCheckRunning) return;
        SharedPreferences prefs = regionPrefs();
        // Ручная проверка из настроек игнорирует и автообновление, и временные гейты
        if (!manual && !prefs.getBoolean(PREF_AUTO_UPDATE, true)) return;
        long now = System.currentTimeMillis();
        if (!manual) {
            long lastCheck = prefs.getLong(PREF_LAST_CHECK, 0L);
            long lastAttempt = prefs.getLong(PREF_LAST_ATTEMPT, 0L);
            if (now - lastCheck < UPDATE_CHECK_INTERVAL) return;   // недавно уже получили ответ от GitHub
            if (now - lastAttempt < UPDATE_RETRY_INTERVAL) return; // недавно пытались и словили сбой — не спамим API
        }
        prefs.edit().putLong(PREF_LAST_ATTEMPT, now).apply();
        updateCheckRunning = true;
        new CheckUpdateTask(manual).execute();
    }

    /** Спрашиваем GitHub (или резервные источники), какая версия сейчас актуальна. */
    private class CheckUpdateTask extends AsyncTask<Void, Void, String[]> {
        private final boolean manual;
        private boolean succeeded; // true, если хотя бы один источник ответил внятно

        CheckUpdateTask(boolean manual) {
            this.manual = manual;
        }

        @Override
        protected String[] doInBackground(Void... ignore) {
            // 1) CDN-зеркало jsDelivr — работает и при блокировке GitHub (РФ):
            //    манифест из тега репозитория, @latest = новейший тег релиза.
            //    Ставим первым: для целевой аудитории это самый быстрый и
            //    надёжный источник.
            String[] res = fetchJsDelivr();
            if (res != null) return res;
            // 2) GitHub API — канон, доступен вне блокировок
            res = fetchGithubApi();
            if (res != null) return res;
            // 3) HTML-страница релиза — тег берём из редиректа, читаем только заголовки
            return fetchGithubHtml();
        }

        /** Источник 1: jsDelivr. Сначала резолвим ТОЧНУЮ последнюю версию через
         *  data.jsdelivr.com (этот API свежий; CDN-кэш @latest может держать
         *  старый контент до ~12 ч после релиза), затем читаем манифест по
         *  пин-версии. Не зависит от доступности GitHub. */
        private String[] fetchJsDelivr() {
            HttpURLConnection conn = null;
            InputStream in = null;
            long t0 = System.currentTimeMillis();
            String version = null;
            try {
                conn = openConn(new URL(JSDELIVR_RESOLVED + System.currentTimeMillis()), 8000);
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    in = conn.getInputStream();
                    JSONObject root = new JSONObject(readAll(in));
                    version = root.optString("version", "");
                }
            } catch (Exception ignored) {
            } finally {
                closeQuietly(in, conn);
                in = null;
                conn = null;
            }
            String manifestUrl = (version != null && !version.isEmpty())
                    ? "https://cdn.jsdelivr.net/gh/Erzkanzler2k/youtube-vot@" + version + "/release-manifest.json"
                    : JSDELIVR_MANIFEST; // запасной вариант: @latest
            try {
                conn = openConn(new URL(manifestUrl), 8000);
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    Log.i(TAG_UPD, "jsdelivr: HTTP " + conn.getResponseCode() + " (" + (System.currentTimeMillis() - t0) + "ms)");
                    return null;
                }
                in = conn.getInputStream();
                JSONObject root = new JSONObject(readAll(in));
                String tag = root.optString("tag", "");
                String apk = root.optString("apk", "");
                if (tag.isEmpty() || apk.isEmpty()) return null;
                succeeded = true;
                Log.i(TAG_UPD, "jsdelivr: tag=" + tag + " (v=" + version + ") (" + (System.currentTimeMillis() - t0) + "ms)");
                return new String[]{tag, apk};
            } catch (Exception e) {
                return null;
            } finally {
                closeQuietly(in, conn);
            }
        }

        /** Источник 2: api.github.com/repos/.../releases/latest */
        private String[] fetchGithubApi() {
            HttpURLConnection conn = null;
            InputStream in = null;
            long t0 = System.currentTimeMillis();
            try {
                conn = openConn(new URL(GITHUB_LATEST_API), 7000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    Log.i(TAG_UPD, "github api: HTTP " + conn.getResponseCode() + " (" + (System.currentTimeMillis() - t0) + "ms)");
                    return null;
                }
                succeeded = true;
                in = conn.getInputStream();
                JSONObject root = new JSONObject(readAll(in));
                String tag = root.optString("tag_name", "");
                if (tag.isEmpty()) return null;
                JSONArray assets = root.optJSONArray("assets");
                if (assets == null) return null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (APK_ASSET_NAME.equals(asset.optString("name", ""))) {
                        // GitHub отдаёт github.com URL, а он в РФ заблокирован —
                        // пересобираем скачивание на CDN-зеркало по тегу.
                        Log.i(TAG_UPD, "github api: tag=" + tag + " (" + (System.currentTimeMillis() - t0) + "ms)");
                        return new String[]{tag, String.format(JSDELIVR_DOWNLOAD_TEMPLATE, tag)};
                    }
                }
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                closeQuietly(in, conn);
            }
        }

        /** Источник 3: github.com/.../releases/latest — редирект на /releases/tag/<версия>. */
        private String[] fetchGithubHtml() {
            HttpURLConnection conn = null;
            long t0 = System.currentTimeMillis();
            try {
                conn = openConn(new URL(GITHUB_LATEST_HTML), 7000);
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                    Log.i(TAG_UPD, "github html: HTTP " + code + " (" + (System.currentTimeMillis() - t0) + "ms)");
                    return null;
                }
                String loc = conn.getHeaderField("Location");
                if (loc == null) return null;
                int idx = loc.indexOf(RELEASE_TAG_MARKER);
                if (idx < 0) return null;
                String tag = loc.substring(idx + RELEASE_TAG_MARKER.length());
                int q = tag.indexOf('?');
                if (q >= 0) tag = tag.substring(0, q);
                int h = tag.indexOf('#');
                if (h >= 0) tag = tag.substring(0, h);
                if (tag.isEmpty() || tag.contains(" ")) return null;
                succeeded = true;
                Log.i(TAG_UPD, "github html: tag=" + tag + " (" + (System.currentTimeMillis() - t0) + "ms)");
                // Скачивание — с CDN-зеркала (github.com в РФ заблокирован)
                return new String[]{tag, String.format(JSDELIVR_DOWNLOAD_TEMPLATE, tag)};
            } catch (Exception e) {
                return null;
            } finally {
                closeQuietly(null, conn);
            }
        }

        private HttpURLConnection openConn(URL url) throws IOException {
            return openConn(url, 15000);
        }

        private HttpURLConnection openConn(URL url, int timeoutMs) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "YouTubeVot");
            return conn;
        }

        private void closeQuietly(InputStream in, HttpURLConnection conn) {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (conn != null) conn.disconnect();
        }

        @Override
        protected void onPostExecute(String[] result) {
            updateCheckRunning = false;
            if (isFinishing() || isDestroyed()) return;
            SharedPreferences prefs = regionPrefs();
            if (!succeeded) {
                // Сбой (нет сети, таймаут, rate-limit GitHub): не ставим метку «проверено» —
                // при следующем старте/возврате в приложение попробуем снова.
                Log.w(TAG_UPD, "update check: все источники недоступны");
                if (manual) {
                    Toast.makeText(MainActivity.this, R.string.update_check_fail, Toast.LENGTH_LONG).show();
                }
                return;
            }
            prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
            if (result == null) {
                if (manual) {
                    Toast.makeText(MainActivity.this, R.string.update_no_new, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            String tag = result[0];
            String url = result[1];
            if (tag.equals(prefs.getString(PREF_SKIP_UPDATE, ""))) {
                Log.i(TAG_UPD, "update check: тег " + tag + " в skip-списке");
                return;
            }
            if (!isNewerVersion(tag)) {
                Log.i(TAG_UPD, "update check: установлена последняя версия (" + tag + ")");
                if (manual) {
                    Toast.makeText(MainActivity.this, R.string.update_no_new, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            Log.i(TAG_UPD, "update check: найдено обновление " + tag);
            showUpdateDialog(tag, url);
        }
    }

    private String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        return sb.toString();
    }

    private boolean isNewerVersion(String latestTag) {
        int[] cur = parseVersion(currentVersionName());
        int[] latest = parseVersion(latestTag);
        if (cur == null || latest == null) return false;
        for (int i = 0; i < 3; i++) {
            if (latest[i] > cur[i]) return true;
            if (latest[i] < cur[i]) return false;
        }
        return false;
    }

    private int[] parseVersion(String v) {
        String clean = (v == null ? "" : v).replaceFirst("^[vV]", "").trim();
        String[] parts = clean.split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    parts.length > 2 ? Integer.parseInt(parts[2]) : 0
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    private void showUpdateDialog(final String tag, final String url) {
        new AlertDialog.Builder(this)
                .setTitle("Обновление YouTube VoT")
                .setMessage("Доступна новая версия " + tag + ".\nСкачать и установить?")
                .setPositiveButton("Обновить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        startUpdateDownload(tag, url);
                    }
                })
                .setNegativeButton("Позже", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        regionPrefs().edit().putString(PREF_SKIP_UPDATE, tag).apply();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void startUpdateDownload(final String tag, final String url) {
        // На Android 8+ установка из «неизвестных источников» требует разрешения
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            SharedPreferences prefs = regionPrefs();
            prefs.edit().putString(PREF_PENDING_URL, url).putString(PREF_PENDING_TAG, tag).apply();
            new AlertDialog.Builder(this)
                    .setTitle("Разрешение на установку")
                    .setMessage("Android просит разрешить установку приложений из этого источника.\n" +
                            "Откройте настройки — загрузка продолжится сразу после возврата.")
                    .setPositiveButton("Открыть настройки", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Exception e) {
                                try {
                                    startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        doDownloadUpdate(url, tag);
    }

    private void doDownloadUpdate(String url, String tag) {
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (downloadManager == null) return;

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setTitle("Обновление YouTube VoT");
        req.setDescription("Скачивание новой версии…");
        req.setMimeType("application/vnd.android.package-archive");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedOverMetered(true);
        req.setAllowedOverRoaming(false);
        // Свой destination не задаём намеренно: на Android 11+ (scoped storage)
        // setDestinationInExternalFilesDir() падает с IllegalStateException
        // «Unable to create directory: …/Android/data/<pkg>/files/Download»
        // и убивает UI-поток ДО enqueue(). Системное хранилище DownloadManager
        // работает на всех версиях, а файл для проверки подписи копируем
        // из content-URI в кэш (см. handleDownloadComplete).
        try {
            currentDownloadId = downloadManager.enqueue(req);
            // id сохраняем в prefs: системный броадкаст о завершении загрузки
            // на API 26+ приходит манифестному receiver'у, а динамический
            // зарегистрирован не во всех сценариях.
            getSharedPreferences(BypassVpnService.PREFS_BYPASS, MODE_PRIVATE)
                    .edit().putLong(PREF_DOWNLOAD_ID, currentDownloadId).apply();
            Toast.makeText(this, "Скачивание обновления…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG_UPD, "download enqueue failed", e);
            Toast.makeText(this, "Не удалось начать обновление", Toast.LENGTH_SHORT).show();
        }
    }

    private void registerDownloadReceiver() {
        if (downloadReceiver != null) return;
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                    if (id == currentDownloadId) {
                        currentDownloadId = -1L;
                        handleDownloadComplete(id);
                    }
                }
            }
        };
        try {
            registerReceiver(downloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception ignored) {
        }
    }

    private void unregisterDownloadReceiver() {
        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }
    }

    private void handleDownloadComplete(long id) {
        // Защита от двойного срабатывания: на API 26+ событие приходит и
        // манифестному receiver'у (он будит Activity), и динамическому — оба
        // могут вызвать обработку для одного id.
        if (handledDownloadId == id) return;
        handledDownloadId = id;
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        Cursor c = null;
        int status = -1;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(this, "Не удалось скачать обновление", Toast.LENGTH_SHORT).show();
            clearDownloadedApk();
            return;
        }
        // Файл лежит в системном хранилище DownloadManager и доступен как
        // content-URI — копируем его в кэш, чтобы PackageManager мог
        // разобрать подпись (getPackageArchiveInfo работает с файлом).
        File cached = copyDownloadedApkToCache(dm, id);
        if (cached == null || !verifyApkSignature(cached)) {
            new AlertDialog.Builder(this)
                    .setTitle("Ошибка проверки")
                    .setMessage("Скачанный файл не прошёл проверку подписи. Обновление отменено.")
                    .setPositiveButton("ОК", null)
                    .show();
            clearDownloadedApk();
            return;
        }
        installApk(id);
    }

    /** Копирует скачанный APK из content-URI DownloadManager в cacheDir. */
    private File copyDownloadedApkToCache(DownloadManager dm, long id) {
        try {
            Uri src = dm.getUriForDownloadedFile(id);
            if (src == null) return null;
            File dir = getCacheDir();
            if (dir == null) dir = getFilesDir();
            if (dir == null) return null;
            File dst = new File(dir, "update.apk");
            InputStream in = getContentResolver().openInputStream(src);
            if (in == null) return null;
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(dst);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            } finally {
                try { if (in != null) in.close(); } catch (IOException ignored) {}
                try { if (out != null) out.close(); } catch (IOException ignored) {}
            }
            downloadedApkFile = dst;
            return dst;
        } catch (Exception e) {
            Log.w(TAG_UPD, "copy downloaded apk failed", e);
            return null;
        }
    }

    private void clearDownloadedApk() {
        if (downloadedApkFile != null) {
            downloadedApkFile.delete();
            downloadedApkFile = null;
        }
    }

    /** Файл подписан тем же ключом, что и установленное приложение. */
    private boolean verifyApkSignature(File apk) {
        try {
            PackageManager pm = getPackageManager();
            if (Build.VERSION.SDK_INT >= 28) {
                // На API 28+ PackageInfo.signatures всегда null — только
                // GET_SIGNING_CERTIFICATES/SigningInfo. Иначе проверка упала бы
                // с «Ошибка проверки» даже для корректно подписанного APK.
                PackageInfo cur = pm.getPackageInfo(getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                PackageInfo update = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (cur == null || update == null
                        || cur.signingInfo == null || update.signingInfo == null) {
                    return false;
                }
                Signature[] a = signaturesOf(cur.signingInfo);
                Signature[] b = signaturesOf(update.signingInfo);
                if (a == null || b == null || a.length == 0 || a.length != b.length) {
                    return false;
                }
                for (int i = 0; i < a.length; i++) {
                    if (!a[i].toCharsString().equals(b[i].toCharsString())) return false;
                }
                return true;
            }
            PackageInfo cur = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            PackageInfo update = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (cur == null || update == null || cur.signatures == null || update.signatures == null
                    || cur.signatures.length == 0 || update.signatures.length == 0) {
                return false;
            }
            return cur.signatures[0].toCharsString().equals(update.signatures[0].toCharsString());
        } catch (Exception e) {
            return false;
        }
    }

    private static Signature[] signaturesOf(SigningInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.hasMultipleSigners()
                    ? info.getApkContentsSigners()
                    : info.getSigningCertificateHistory();
        }
        return null;
    }

    private void installApk(long id) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Log.w(TAG_UPD, "install: нет разрешения на установку из источника");
            Toast.makeText(this, "Сначала разрешите установку из этого источника", Toast.LENGTH_LONG).show();
            return;
        }
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm == null) return;
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) {
            Log.w(TAG_UPD, "install: getUriForDownloadedFile вернул null");
            Toast.makeText(this, "Не удалось открыть файл обновления", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.i(TAG_UPD, "install: открываю установщик, uri=" + uri);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG_UPD, "install: установщик не найден", e);
            Toast.makeText(this, "Не найден установщик приложений", Toast.LENGTH_SHORT).show();
        }
    }

    /** Если загрузка была отложена до разрешения — продолжаем её после возврата. */
    private void checkPendingUpdateDownload() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            if (!getPackageManager().canRequestPackageInstalls()) return;
            SharedPreferences prefs = regionPrefs();
            if (prefs.contains(PREF_PENDING_URL)) {
                String url = prefs.getString(PREF_PENDING_URL, "");
                String tag = prefs.getString(PREF_PENDING_TAG, "");
                prefs.edit().remove(PREF_PENDING_URL).remove(PREF_PENDING_TAG).apply();
                if (!url.isEmpty()) doDownloadUpdate(url, tag);
            }
        } catch (Exception ignored) {
        }
    }

    /** Плавное исчезновение сплэша после первой загрузки страницы. */
    private void hideSplash() {
        if (splashOverlay == null || splashOverlay.getVisibility() != View.VISIBLE) return;
        checkForUpdate(false); // авто-проверка, один раз после первой загрузки (повтор — в onResume)
        if (splashPulse != null) {
            splashPulse.cancel();
            splashPulse = null;
        }
        splashOverlay.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
            @Override
            public void run() {
                splashOverlay.setVisibility(View.GONE);
                splashOverlay.setAlpha(1f);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            web.getWebChromeClient().onHideCustomView();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        checkPendingUpdateDownload();
        checkForUpdate(false); // повторная авто-проверка после возврата в приложение (с временным гейтом)
    }

    @Override
    protected void onDestroy() {
        unregisterNetworkCallback();
        unregisterDownloadReceiver();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ---------------- Сохранение файлов (аудио-дорожка, субтитры) ---------------- */

    private static final class SaveFileTask extends AsyncTask<String, Void, String> {
        private final Context ctx;

        SaveFileTask(Context ctx) {
            this.ctx = ctx.getApplicationContext();
        }

        @Override
        protected String doInBackground(String... params) {
            String url = params[0];
            String userAgent = params[1];
            String contentDisposition = params[2];
            String mimetype = params[3];
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
            if (fileName == null || fileName.isEmpty()) fileName = "download.bin";

            HttpURLConnection conn = null;
            InputStream in = null;
            File tmp = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.connect();
                in = conn.getInputStream();

                // Качаем во временный файл
                tmp = File.createTempFile("vot_dl_", ".tmp", ctx.getCacheDir());
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[65536];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    total += n;
                    if (total > 500L * 1024 * 1024) throw new Exception("Файл слишком большой");
                }
                fos.close();

                // Перемещаем в Downloads
                File dest;
                if (Build.VERSION.SDK_INT >= 29) {
                    dest = saveViaMediaStore(tmp, fileName);
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists() && !dir.mkdirs()) dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    dest = new File(dir, fileName);
                    InputStream src = new java.io.FileInputStream(tmp);
                    FileOutputStream dst = new FileOutputStream(dest);
                    byte[] copyBuf = new byte[65536];
                    int cn;
                    while ((cn = src.read(copyBuf)) != -1) dst.write(copyBuf, 0, cn);
                    src.close();
                    dst.close();
                }
                return dest == null ? null : dest.getAbsolutePath();
            } catch (Exception e) {
                return null;
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
                if (tmp != null) tmp.delete();
            }
        }

        private File saveViaMediaStore(File tmp, String fileName) throws Exception {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, urlToMime(fileName));
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            InputStream in = new java.io.FileInputStream(tmp);
            java.io.OutputStream out = ctx.getContentResolver().openOutputStream(uri);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            out.close();
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, values, null, null);
            return new File(uri.getPath());
        }

        private String urlToMime(String fileName) {
            String l = fileName.toLowerCase();
            if (l.endsWith(".mp3")) return "audio/mpeg";
            if (l.endsWith(".srt")) return "application/x-subrip";
            if (l.endsWith(".vtt")) return "text/vtt";
            if (l.endsWith(".json")) return "application/json";
            return "application/octet-stream";
        }

        @Override
        protected void onPostExecute(String path) {
            if (path == null) {
                Toast.makeText(ctx, R.string.download_failed, Toast.LENGTH_LONG).show();
                return;
            }
            String msg = ctx.getString(R.string.download_saved, new File(path).getName());
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        }
    }
}