/*
 * MainActivity — Android-клиент YouTube со встроенным voice-over-translation (VoT).
 *
 * Сборка: ручной пайплайн (aapt2 -> javac -> d8 -> zipalign -> apksigner), без AndroidX.
 * Java 8-совместимый код (без стримов, Records и т.п.).
 */
package com.vot.youtube;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://m.youtube.com/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36";

    private static final String VOT_BOOTSTRAP = "vot/bootstrap.js";
    private static final String VOT_BUNDLE = "vot/vot.user.js";

    private WebView web;
    private FrameLayout customViewContainer;
    private View topBar;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ProgressBar progressBar;
    private ImageView btnBack, btnForward;

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
        web = findViewById(R.id.web);
        progressBar = findViewById(R.id.progress);
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
                view.setAlpha(0f); // плавное появление вместо резкой смены кадра
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                updateNavState();
                view.animate().alpha(1f).setDuration(220).start();
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
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
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
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (web.canGoBack()) web.goBack();
            }
        });
        btnForward.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (web.canGoForward()) web.goForward();
            }
        });
        ImageView refresh = findViewById(R.id.btn_refresh);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                web.reload();
            }
        });
        ImageView exit = findViewById(R.id.btn_exit);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                finish();
            }
        });

        String initial = HOME_URL;
        Uri data = getIntent().getData();
        if (data != null && (data.getHost() == null || data.getHost().contains("youtube") || "youtu.be".equals(data.getHost()))) {
            initial = data.toString();
        }
        web.loadUrl(initial);
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
    }

    @Override
    protected void onDestroy() {
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