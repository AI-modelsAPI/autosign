package icu.justwoker.justsign;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** 公开 GitHub Release 更新：仅官方 APK，校验包名、版本与签名后交给系统安装器。 */
public final class UpdateChecker {
    private static final String REPO = "AI-modelsAPI/autosign";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long AUTO_INTERVAL = 24L * 60L * 60L * 1000L;
    private static final String PREFS = "autosign_update", LAST_AUTO = "last_auto_check";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).followRedirects(true).build();
    private static volatile Release available;
    private static volatile int progress = -1;
    private static volatile File pendingInstall;
    private static volatile Runnable listener;
    private UpdateChecker() {}

    public static void setListener(Runnable value) { listener = value; }
    public static boolean hasUpdate() { return available != null; }
    public static String updateLabel() {
        if (progress >= 0) return "下载中 " + progress + "%";
        Release r = available; return r == null ? "" : "发现新版本 v" + r.version;
    }
    public static void auto(MainActivity activity) {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (now - prefs.getLong(LAST_AUTO, 0L) < AUTO_INTERVAL) return;
        prefs.edit().putLong(LAST_AUTO, now).apply(); check(activity, false);
    }
    public static void manual(MainActivity activity) { check(activity, true); }
    public static void download(MainActivity activity) {
        Release release = available; if (release == null || progress >= 0) return;
        new Thread(() -> downloadAndVerify(activity, release), "update-download").start();
    }
    public static void resumeInstall(MainActivity activity) {
        File file = pendingInstall; if (file != null && file.isFile() && canInstall(activity)) install(activity, file);
    }

    private static void check(MainActivity activity, boolean manual) {
        new Thread(() -> {
            Release found = null;
            try {
                Request request = new Request.Builder().url(API).header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "AutoSign-Android").build();
                try (Response response = HTTP.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) found = parseRelease(response.body().string());
                }
            } catch (Exception ignored) {}
            final Release result = found;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                String local = localVersion(activity);
                available = result != null && compare(result.version, local) > 0 ? result : null;
                notifyChanged();
                if (available != null) showFound(activity, available.version);
                else if (manual) activity.toast(result == null ? "暂时无法检查更新，请稍后重试" : "当前已是最新版本 v" + local);
            });
        }, "release-check").start();
    }

    private static Release parseRelease(String json) throws Exception {
        JSONObject root = new JSONObject(json); String version = normalize(root.optString("tag_name"));
        if (version.isEmpty() || root.optBoolean("draft") || root.optBoolean("prerelease")) return null;
        String expected = "AutoSign-" + version + "-release.apk"; JSONArray assets = root.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && expected.equals(asset.optString("name"))) {
                String url = asset.optString("browser_download_url");
                if (validAssetUrl(url, version)) return new Release(version, url, asset.optLong("size", -1));
            }
        }
        return null;
    }

    private static void downloadAndVerify(MainActivity activity, Release release) {
        File out = new File(new File(activity.getCacheDir(), "updates"), "AutoSign-" + release.version + "-release.apk");
        try {
            if (!out.getParentFile().exists() && !out.getParentFile().mkdirs()) throw new Exception("无法创建下载目录");
            progress = 0; notifyChanged();
            Request request = new Request.Builder().url(release.url).header("User-Agent", "AutoSign-Android").build();
            try (Response response = HTTP.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new Exception("下载 HTTP " + response.code());
                if (!validFinalUrl(response.request().url().toString())) throw new Exception("下载地址不可信");
                ResponseBody body = response.body(); long total = body.contentLength();
                if (release.size > 0 && total > 0 && release.size != total) throw new Exception("文件大小不符");
                try (java.io.InputStream in = body.byteStream(); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buffer = new byte[32768]; long done = 0; int n, last = -1;
                    while ((n = in.read(buffer)) >= 0) { fos.write(buffer, 0, n); done += n;
                        int now = total > 0 ? (int)(done * 100 / total) : 0;
                        if (now != last) { last = progress = now; notifyChanged(); }
                    }
                }
            }
            verifyApk(activity, out, release.version); pendingInstall = out; progress = -1; notifyChanged();
            activity.runOnUiThread(() -> requestInstall(activity, out));
        } catch (Exception e) {
            out.delete(); progress = -1; notifyChanged();
            activity.runOnUiThread(() -> activity.toast("更新下载失败：" + safeMessage(e)));
        }
    }

    private static void verifyApk(Context context, File apk, String expectedVersion) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo current = pm.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) throw new Exception("APK 包名不符");
        if (!expectedVersion.equals(normalize(archive.versionName)) || compare(archive.versionName, current.versionName) <= 0)
            throw new Exception("APK 版本无效");
        if (!signatureDigest(archive).equals(signatureDigest(current))) throw new Exception("APK 签名不一致");
    }
    private static String signatureDigest(PackageInfo info) throws Exception {
        android.content.pm.Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) throw new Exception("缺少签名");
        return bytesToHex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }
    private static void requestInstall(MainActivity activity, File file) {
        if (!canInstall(activity) && Build.VERSION.SDK_INT >= 26) {
            activity.toast("请允许 AutoSign 安装未知应用，返回后将继续安装");
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()))); return;
        }
        install(activity, file);
    }
    private static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }
    private static void install(MainActivity activity, File file) {
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", file);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) { activity.toast("无法打开系统安装器"); }
    }
    private static void showFound(MainActivity activity, String version) {
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("发现新版本 v" + version)
                .setMessage("设置页已出现新版本按钮。你可以现在下载，也可以稍后处理。")
                .setPositiveButton("下载并安装", (d, w) -> download(activity)).setNegativeButton("稍后", null).create();
        dialog.setOnShowListener(x -> Ui.styleDialog(dialog)); dialog.show();
    }
    private static void notifyChanged() { Runnable value = listener; if (value != null) value.run(); }
    private static String localVersion(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { return "0"; }
    }
    static boolean validAssetUrl(String url, String version) {
        return url != null && url.equals("https://github.com/" + REPO + "/releases/download/v" + version
                + "/AutoSign-" + version + "-release.apk");
    }
    private static boolean validFinalUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try { String host = Uri.parse(url).getHost(); return "github.com".equals(host) || "objects.githubusercontent.com".equals(host)
                || (host != null && host.endsWith(".githubusercontent.com")); }
        catch (Exception e) { return false; }
    }
    static String normalize(String version) {
        if (version == null) return ""; String value = version.trim();
        if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1);
        int dash = value.indexOf('-'); if (dash >= 0) value = value.substring(0, dash);
        return value.matches("\\d+(\\.\\d+)*") ? value : "";
    }
    static int compare(String a, String b) {
        String na = normalize(a), nb = normalize(b); if (na.isEmpty() || nb.isEmpty()) return 0;
        String[] aa = na.split("\\."), bb = nb.split("\\."); int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) { int av = i < aa.length ? Integer.parseInt(aa[i]) : 0;
            int bv = i < bb.length ? Integer.parseInt(bb[i]) : 0; if (av != bv) return Integer.compare(av, bv); }
        return 0;
    }
    private static String bytesToHex(byte[] bytes) { StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(Locale.US, "%02x", value)); return out.toString(); }
    private static String safeMessage(Exception e) { String m = e.getMessage(); return m == null || m.length() > 80 ? "请稍后重试" : m; }
    private static final class Release { final String version, url; final long size;
        Release(String version, String url, long size) { this.version = version; this.url = url; this.size = size; } }
}