package com.openkhub.sensefield;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Checks for releases and downloads their APK into app-private storage. */
public final class AppUpdateClient implements AutoCloseable {
    private static final String UPDATE_PREFS = "app_update_client";
    private static final String INSTALLED_APK_SHA256 = "installed_apk_sha256";
    private static final String INSTALLED_APK_IDENTITY = "installed_apk_identity";
    private static final long MAX_METADATA_BYTES = 1024L * 1024L;
    private static final long MAX_NOTES_CHARS = 16_384L;
    private static final int MAX_REDIRECTS = 5;
    private static final long METADATA_TIMEOUT_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(15L);
    private static final long STALE_PART_AGE_MS =
            java.util.concurrent.TimeUnit.HOURS.toMillis(1L);
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final ExecutorService TRANSPORT_CLEANUP = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sensefield-updater-transport-close");
        thread.setDaemon(true);
        return thread;
    });

    private final Context appContext;
    private final HttpUrl metadataUrl;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sensefield-app-updater");
        thread.setDaemon(true);
        return thread;
    });
    private final OkHttpClient metadataHttpClient;
    private final OkHttpClient httpClient;
    private final Object stateLock = new Object();
    private final AtomicLong generation = new AtomicLong(0L);
    private Job activeJob;
    private boolean closed;

    public AppUpdateClient(Context context, String configuredMetadataUrl) {
        if (context == null) {
            throw new IllegalArgumentException("Context is required.");
        }
        this.appContext = context.getApplicationContext();
        this.metadataUrl = configuredMetadataUrl == null
                || configuredMetadataUrl.trim().isEmpty()
                ? null : validateMetadataUrl(configuredMetadataUrl);
        OkHttpClient networkDefaults = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.metadataHttpClient = networkDefaults.newBuilder()
                .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.httpClient = networkDefaults.newBuilder()
                .callTimeout(10, java.util.concurrent.TimeUnit.MINUTES)
                .build();
    }

    public interface CheckCallback {
        void onUpdate(AppUpdateRelease release);

        void onNoUpdate();

        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(long downloadedBytes, long totalBytes);

        void onDownloaded(File apkFile);

        void onError(String message);
    }

    /** Returns whether a valid update manifest URL was supplied. */
    public boolean isConfigured() {
        return metadataUrl != null;
    }

    /** Runs one metadata request on the worker. All callbacks are posted to the main thread. */
    public void check(String localVersionName, long localVersionCode, CheckCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback is required.");
        }
        startJob(callback::onError, job -> {
            AppUpdateRelease release = checkBlocking(job, localVersionName, localVersionCode);
            if (release == null) {
                post(job, callback::onNoUpdate);
            } else {
                post(job, () -> callback.onUpdate(release));
            }
        });
    }

    /** Downloads a previously returned release and reports its verified private file. */
    public void download(AppUpdateRelease release, DownloadCallback callback) {
        if (release == null || callback == null) {
            throw new IllegalArgumentException("Release and callback are required.");
        }
        if (!release.getPackageName().equals(appContext.getPackageName())) {
            postImmediate(callback::onError, "此更新包不适用于当前应用。");
            return;
        }
        startJob(callback::onError, job -> {
            File apk = downloadBlocking(job, release,
                    (downloaded, total) -> post(job, () ->
                            callback.onProgress(downloaded, total)));
            post(job, () -> callback.onDownloaded(apk));
        });
    }

    /** Cancels the current job; the client can be reused immediately. */
    public void cancel() {
        Job job;
        synchronized (stateLock) {
            generation.incrementAndGet();
            job = activeJob;
            activeJob = null;
        }
        if (job != null) {
            job.cancelled = true;
            TRANSPORT_CLEANUP.execute(job::cancel);
        }
    }

    @Override
    public void close() {
        Job job;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            generation.incrementAndGet();
            job = activeJob;
            activeJob = null;
        }
        if (job != null) job.cancelled = true;
        worker.shutdownNow();
        TRANSPORT_CLEANUP.execute(() -> {
            if (job != null) job.cancel();
            metadataHttpClient.dispatcher().cancelAll();
            httpClient.dispatcher().cancelAll();
            // TLS close_notify can write to a socket even for an idle connection.
            httpClient.connectionPool().evictAll();
        });
    }

    private void startJob(ErrorCallback onBusy, JobWork work) {
        Job job;
        synchronized (stateLock) {
            if (closed) {
                postImmediate(onBusy, "更新服务已关闭。");
                return;
            }
            if (activeJob != null) {
                postImmediate(onBusy, "已有更新任务正在运行，请稍候。");
                return;
            }
            job = new Job(generation.incrementAndGet());
            activeJob = job;
        }
        try {
            worker.execute(() -> {
                try {
                    work.run(job);
                } catch (UpdateFailure failure) {
                    post(job, () -> onBusy.onError(failure.getMessage()));
                } catch (InterruptedIOException interrupted) {
                    if (!job.isCancelled()) {
                        post(job, () -> onBusy.onError("更新请求已中断，请重试。"));
                    }
                } catch (IOException io) {
                    if (!job.isCancelled()) {
                        post(job, () -> onBusy.onError("暂时无法连接更新服务，请检查网络后重试。"));
                    }
                } catch (RuntimeException runtime) {
                    if (!job.isCancelled()) {
                        post(job, () -> onBusy.onError("更新服务返回的数据无法处理，请稍后重试。"));
                    }
                } finally {
                    synchronized (stateLock) {
                        if (activeJob == job) {
                            activeJob = null;
                        }
                    }
                }
            });
        } catch (RuntimeException rejected) {
            synchronized (stateLock) {
                if (activeJob == job) {
                    activeJob = null;
                }
            }
            post(job, () -> onBusy.onError("更新服务暂不可用，请稍后重试。"));
        }
    }

    private AppUpdateRelease checkBlocking(Job job, String localVersionName,
                                          long localVersionCode) throws IOException {
        if (metadataUrl == null) {
            throw new UpdateFailure("尚未配置更新清单地址。");
        }
        HttpUrl finalMetadataUrl;
        try (Response response = executeFollowingRedirects(metadataUrl, job,
                RedirectPolicy.metadata(metadataUrl))) {
            if (!response.isSuccessful()) {
                throw httpFailure(response.code());
            }
            finalMetadataUrl = response.request().url();
            byte[] body = readBounded(response.body(), MAX_METADATA_BYTES,
                    "更新清单过大，无法安全读取。");
            JSONObject json;
            try {
                json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (JSONException malformed) {
                throw new UpdateFailure("更新服务返回了无效数据。");
            }
            checkCancelled(job);
            AppUpdateRelease release = parseSelfHostedManifest(json, localVersionCode,
                    appContext.getPackageName(), finalMetadataUrl);
            if (release != null && release.getVersionCode() == localVersionCode) {
                String installedHash = installedApkSha256(appContext);
                if (isSameVersionNoOp(release, localVersionName, localVersionCode,
                        installedHash)) {
                    return null;
                }
            }
            return release;
        } finally {
            job.clearCall();
        }
    }

    private File downloadBlocking(Job job, AppUpdateRelease release,
                                  ProgressCallback progress) throws IOException {
        if (release.getBytes() <= 0 || release.getBytes() > AppUpdateRelease.MAX_APK_BYTES
                || !isValidSha256(release.getSha256())) {
            throw new UpdateFailure("更新包信息无效，无法继续下载。");
        }
        validateSameOriginApkUrl(release.apkHttpUrl(), release.metadataUrl());

        File updateDirectory = new File(appContext.getFilesDir(), "updates");
        if (!updateDirectory.isDirectory() && !updateDirectory.mkdirs()) {
            throw new UpdateFailure("无法准备更新文件存储空间。");
        }
        cleanupStalePartFiles(updateDirectory, System.currentTimeMillis());
        File verifiedFile = verifiedFileFor(updateDirectory, release.getSha256());
        if (verifiedFile.isFile()) {
            try {
                verifyDownloadedArtifact(verifiedFile, release.getBytes(), release.getSha256());
                cleanupOldVerifiedPackages(updateDirectory, verifiedFile);
                progress.onProgress(release.getBytes(), release.getBytes());
                return verifiedFile;
            } catch (IOException invalidCache) {
                if (!verifiedFile.delete()) {
                    throw new UpdateFailure("无法准备更新文件存储空间。");
                }
            }
        }
        final File partFile;
        try {
            partFile = createPartFile(updateDirectory);
        } catch (IOException cannotCreatePart) {
            throw new UpdateFailure("无法准备更新文件存储空间。");
        }
        long downloaded = 0L;
        long lastProgressAt = 0L;
        try (Response response = executeFollowingRedirects(release.apkHttpUrl(), job,
                RedirectPolicy.apk(release))) {
            if (!response.isSuccessful()) {
                throw httpFailure(response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new UpdateFailure("下载到的更新包为空。");
            }
            long contentLength = responseBody.contentLength();
            if (contentLength > AppUpdateRelease.MAX_APK_BYTES
                    || (contentLength >= 0 && contentLength != release.getBytes())) {
                throw new UpdateFailure("下载文件大小与发布信息不一致，请重试。");
            }
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[32 * 1024];
            try (InputStream input = responseBody.byteStream();
                 FileOutputStream output = new FileOutputStream(partFile, false)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled(job);
                    if (downloaded + count > release.getBytes()
                            || downloaded + count > AppUpdateRelease.MAX_APK_BYTES) {
                        throw new UpdateFailure("下载文件超过发布信息标注的大小。");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    downloaded += count;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (downloaded == release.getBytes()
                            || now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                        lastProgressAt = now;
                        progress.onProgress(downloaded, release.getBytes());
                    }
                }
                output.getFD().sync();
            }
            checkCancelled(job);
            if (downloaded != release.getBytes()) {
                throw new UpdateFailure("下载文件大小与发布信息不一致，请重试。");
            }
            String actualHash = toHex(digest.digest());
            if (!constantTimeEquals(actualHash, release.getSha256().toLowerCase(Locale.US))) {
                throw new UpdateFailure("下载文件校验失败，请重新下载。");
            }

            verifiedFile = verifiedFileFor(updateDirectory, release.getSha256());
            if (verifiedFile.exists() && !verifiedFile.delete()) {
                throw new UpdateFailure("无法保存已校验的更新包。");
            }
            if (!partFile.renameTo(verifiedFile)) {
                throw new UpdateFailure("无法保存已校验的更新包。");
            }
            cleanupOldVerifiedPackages(updateDirectory, verifiedFile);
            return verifiedFile;
        } catch (IOException | RuntimeException failure) {
            if (partFile.exists()) {
                // This file is the current job's private temporary package.
                //noinspection ResultOfMethodCallIgnored
                partFile.delete();
            }
            throw failure;
        } finally {
            job.clearCall();
        }
    }

    private static File verifiedFileFor(File updateDirectory, String sha256) {
        return new File(updateDirectory,
                "update-" + sha256.toLowerCase(Locale.US) + ".apk");
    }

    static File createPartFile(File updateDirectory) throws IOException {
        return File.createTempFile("update-", ".part", updateDirectory);
    }

    static void cleanupStalePartFiles(File updateDirectory, long nowMillis) {
        File[] files = updateDirectory.listFiles();
        if (files == null) {
            return;
        }
        long staleBefore = nowMillis - STALE_PART_AGE_MS;
        for (File file : files) {
            String name = file.getName();
            boolean ownedPart = name.equals("update.part")
                    || (name.startsWith("update-") && name.endsWith(".part"));
            if (ownedPart && file.isFile() && file.lastModified() <= staleBefore) {
                // A current download has a ten-minute call limit; older partials are abandoned.
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static void cleanupOldVerifiedPackages(File updateDirectory, File keep) {
        File[] files = updateDirectory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.equals(keep) || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (name.matches("update-[0-9a-fA-F]{64}\\.apk")) {
                // Only remove completed package files created by this updater.
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private Response executeFollowingRedirects(HttpUrl startUrl, Job job,
                                              RedirectPolicy policy) throws IOException {
        HttpUrl url = startUrl;
        long metadataDeadline = policy.metadata
                ? System.nanoTime() + METADATA_TIMEOUT_NANOS : 0L;
        for (int redirects = 0; ; redirects++) {
            checkCancelled(job);
            Request.Builder request = new Request.Builder().url(url).get()
                    .header("User-Agent", "SenseField-Android-Updater");
            if (policy.metadata) {
                request.header("Cache-Control", "no-cache");
            }
            OkHttpClient client = policy.metadata ? metadataHttpClient : httpClient;
            Call call = client.newCall(request.build());
            if (policy.metadata) {
                long remainingNanos = metadataDeadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new UpdateFailure("查询更新耗时过长，请检查网络后重试。");
                }
                call.timeout().timeout(remainingNanos,
                        java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            Response response;
            try {
                if (job.isCancelled()) {
                    call.cancel();
                    throw new InterruptedIOException("Cancelled");
                }
                response = executeAndRetainCall(job.calls, call);
            } catch (IOException | RuntimeException failure) {
                job.clearCall(call);
                throw failure;
            }
            if (!isRedirect(response.code())) {
                return response;
            }
            String location = response.header("Location");
            HttpUrl next = location == null ? null : url.resolve(location);
            response.close();
            job.clearCall(call);
            if (redirects >= MAX_REDIRECTS || next == null) {
                throw new UpdateFailure("更新服务返回了无效跳转地址。");
            }
            if (!policy.allows(url, next)) {
                throw new UpdateFailure("更新服务跳转到不受信任的地址，已停止下载。");
            }
            url = next;
        }
    }

    private void post(Job job, Runnable callback) {
        mainHandler.post(() -> {
            synchronized (stateLock) {
                if (closed || generation.get() != job.generation || job.isCancelled()) {
                    return;
                }
            }
            callback.run();
        });
    }

    private void postImmediate(ErrorCallback callback, String message) {
        long expectedGeneration = generation.get();
        mainHandler.post(() -> {
            synchronized (stateLock) {
                if (closed || generation.get() != expectedGeneration) {
                    return;
                }
            }
            callback.onError(message);
        });
    }

    private static UpdateFailure httpFailure(int statusCode) {
        return new UpdateFailure("更新服务暂时无法处理请求（错误码 " + statusCode + "），请稍后重试。");
    }

    static AppUpdateRelease parseSelfHostedManifest(JSONObject manifest, long localVersionCode,
                                                    String expectedPackageName,
                                                    HttpUrl metadataUrl) throws UpdateFailure {
        if (manifest == null || expectedPackageName == null || metadataUrl == null) {
            throw new UpdateFailure("更新清单信息不完整。");
        }
        if (longField(manifest, "schema_version") != 1L) {
            throw new UpdateFailure("更新清单版本不受支持。");
        }
        String packageName = stringField(manifest, "package_name");
        if (!expectedPackageName.equals(packageName)) {
            throw new UpdateFailure("该更新不适用于当前应用。");
        }
        String versionName = stringField(manifest, "version_name");
        if (!isNumericVersion(versionName)) {
            throw new UpdateFailure("更新版本名称无效。");
        }
        long code = longField(manifest, "version_code");
        if (code <= 0) {
            throw new UpdateFailure("更新版本号无效。");
        }
        long bytes = longField(manifest, "apk_bytes");
        if (bytes <= 0 || bytes > AppUpdateRelease.MAX_APK_BYTES) {
            throw new UpdateFailure("更新 APK 超出允许的大小范围。");
        }
        String hash = stringField(manifest, "apk_sha256");
        if (!isValidSha256(hash)) {
            throw new UpdateFailure("更新 APK 缺少有效的 SHA-256 校验值。");
        }
        HttpUrl apkUrl;
        try {
            apkUrl = HttpUrl.get(stringField(manifest, "apk_url"));
            validateSameOriginApkUrl(apkUrl, metadataUrl);
        } catch (IllegalArgumentException invalidUrl) {
            throw new UpdateFailure("更新 APK 下载地址无效。");
        }
        String notes = optionalStringField(manifest, "release_notes");
        if (notes.length() > MAX_NOTES_CHARS) {
            notes = notes.substring(0, (int) MAX_NOTES_CHARS);
        }
        if (code < localVersionCode) {
            throw new UpdateFailure("更新版本号低于当前已安装版本。");
        }
        return new AppUpdateRelease(packageName, versionName, code, apkUrl, bytes,
                hash.toLowerCase(Locale.US), notes, metadataUrl);
    }

    static int compareNumericVersions(String left, String right) throws UpdateFailure {
        String[] leftParts = numericVersionParts(left);
        String[] rightParts = numericVersionParts(right);
        for (int index = 0; index < 3; index++) {
            // BigInteger avoids overflow and comparison remains numeric for large components.
            java.math.BigInteger l = new java.math.BigInteger(leftParts[index]);
            java.math.BigInteger r = new java.math.BigInteger(rightParts[index]);
            int order = l.compareTo(r);
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }

    static HttpUrl validateMetadataUrl(String value) {
        HttpUrl url;
        try {
            url = HttpUrl.get(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("更新清单地址必须是有效的 HTTPS 地址。");
        }
        if (!url.isHttps() || !hasNoUserInfoOrFragment(url) || url.encodedQuery() != null) {
            throw new IllegalArgumentException(
                    "更新清单地址必须使用 HTTPS，且不能包含凭据、查询参数或片段。");
        }
        return url;
    }

    static void validateSameOriginApkUrl(HttpUrl apkUrl, HttpUrl metadataUrl) {
        if (apkUrl == null || metadataUrl == null || !apkUrl.isHttps()
                || !sameOrigin(apkUrl, metadataUrl) || !hasNoUserInfoOrFragment(apkUrl)
                || apkUrl.encodedQuery() != null) {
            throw new IllegalArgumentException("APK 地址必须来自更新清单所在的 HTTPS 网站。");
        }
    }

    static boolean isValidSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    static void verifyDownloadedArtifact(File file, long expectedBytes, String expectedSha256)
            throws IOException {
        if (file == null || !file.isFile() || expectedBytes <= 0
                || expectedBytes > AppUpdateRelease.MAX_APK_BYTES
                || file.length() != expectedBytes || !isValidSha256(expectedSha256)) {
            throw new IOException("下载包大小或校验信息无效。");
        }
        MessageDigest digest = sha256Digest();
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            long total = 0L;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > expectedBytes || total > AppUpdateRelease.MAX_APK_BYTES) {
                    throw new IOException("下载包超过预期大小。");
                }
                digest.update(buffer, 0, count);
            }
            if (total != expectedBytes || !constantTimeEquals(toHex(digest.digest()),
                    expectedSha256.toLowerCase(Locale.US))) {
                throw new IOException("下载包 SHA-256 校验失败。");
            }
        }
    }

    static boolean isSameVersionNoOp(AppUpdateRelease candidate, String installedVersionName,
                                     long installedVersionCode, String installedSha256)
            throws UpdateFailure {
        if (candidate == null || installedVersionName == null
                || candidate.getVersionCode() < installedVersionCode) {
            throw new UpdateFailure("更新版本号低于当前已安装版本。");
        }
        if (candidate.getVersionCode() > installedVersionCode) {
            return false;
        }
        if (!candidate.getVersionName().equals(installedVersionName)) {
            throw new UpdateFailure("版本号相同但版本名称不同，已拒绝此更新。");
        }
        if (!isValidSha256(installedSha256)) {
            throw new UpdateFailure("无法校验当前安装包，暂时不能检查同版本修订。");
        }
        return constantTimeEquals(candidate.getSha256().toLowerCase(Locale.US),
                installedSha256.toLowerCase(Locale.US));
    }

    static String installedApkSha256(Context context) throws IOException {
        if (context == null) {
            throw new UpdateFailure("无法读取当前安装包，暂时不能检查同版本修订。");
        }
        final PackageInfo installed;
        try {
            installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException | SecurityException missing) {
            throw new UpdateFailure("无法读取当前安装包，暂时不能检查同版本修订。");
        }
        String sourcePath = context.getApplicationInfo().sourceDir;
        File source = sourcePath == null ? null : new File(sourcePath);
        if (source == null || !source.isFile()) {
            throw new UpdateFailure("无法读取当前安装包，暂时不能检查同版本修订。");
        }
        String identity = installed.lastUpdateTime + "\n" + source.getAbsolutePath() + "\n"
                + source.length() + "\n" + source.lastModified();
        SharedPreferences preferences = context.getSharedPreferences(UPDATE_PREFS,
                Context.MODE_PRIVATE);
        String cachedIdentity = preferences.getString(INSTALLED_APK_IDENTITY, null);
        String cachedHash = preferences.getString(INSTALLED_APK_SHA256, null);
        if (identity.equals(cachedIdentity) && isValidSha256(cachedHash)) {
            return cachedHash;
        }
        String hash = sha256File(source);
        preferences.edit().putString(INSTALLED_APK_IDENTITY, identity)
                .putString(INSTALLED_APK_SHA256, hash).apply();
        return hash;
    }

    static String sha256File(File file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return toHex(digest.digest());
    }

    private static byte[] readBounded(ResponseBody body, long maxBytes, String message)
            throws IOException {
        if (body == null) {
            throw new UpdateFailure("更新服务没有返回内容。");
        }
        long contentLength = body.contentLength();
        if (contentLength > maxBytes) {
            throw new UpdateFailure(message);
        }
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            long total = 0L;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new UpdateFailure(message);
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String stringField(JSONObject json, String field) throws UpdateFailure {
        Object value = json.opt(field);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new UpdateFailure("更新服务返回的版本信息不完整。");
        }
        return (String) value;
    }

    private static String optionalStringField(JSONObject json, String field) throws UpdateFailure {
        Object value = json.opt(field);
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new UpdateFailure("更新服务返回的更新说明格式无效。");
        }
        return (String) value;
    }

    private static long longField(JSONObject json, String field) throws UpdateFailure {
        Object value = json.opt(field);
        if (!(value instanceof Number)) {
            throw new UpdateFailure("更新服务返回的版本信息无效。");
        }
        try {
            java.math.BigDecimal decimal = new java.math.BigDecimal(value.toString());
            return decimal.longValueExact();
        } catch (NumberFormatException | ArithmeticException invalidNumber) {
            throw new UpdateFailure("更新服务返回的版本信息无效。");
        }
    }

    private static String[] numericVersionParts(String version) throws UpdateFailure {
        if (version == null) {
            throw new UpdateFailure("当前应用版本信息无效。");
        }
        Matcher matcher = SEMVER.matcher(version);
        if (!matcher.matches() || matcher.group(1).length() > 9
                || matcher.group(2).length() > 9 || matcher.group(3).length() > 9) {
            throw new UpdateFailure("当前应用版本号格式不受支持。");
        }
        return new String[]{matcher.group(1), matcher.group(2), matcher.group(3)};
    }

    private static boolean isNumericVersion(String version) {
        if (version == null) {
            return false;
        }
        Matcher matcher = SEMVER.matcher(version);
        return matcher.matches() && matcher.group(1).length() <= 9
                && matcher.group(2).length() <= 9 && matcher.group(3).length() <= 9;
    }

    private static boolean hasNoUserInfoOrFragment(HttpUrl url) {
        return url.username().isEmpty() && url.password().isEmpty() && url.fragment() == null;
    }

    private static boolean sameOrigin(HttpUrl left, HttpUrl right) {
        return left.isHttps() && right.isHttps() && left.host().equals(right.host())
                && left.port() == right.port();
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 300 || statusCode == 301 || statusCode == 302
                || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static void checkCancelled(Job job) throws InterruptedIOException {
        if (job.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Cancelled");
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Android runtime.", impossible);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class RedirectPolicy {
        private final boolean metadata;
        private final HttpUrl metadataOrigin;

        private RedirectPolicy(boolean metadata, HttpUrl metadataOrigin) {
            this.metadata = metadata;
            this.metadataOrigin = metadataOrigin;
        }

        static RedirectPolicy metadata(HttpUrl origin) {
            return new RedirectPolicy(true, origin);
        }

        static RedirectPolicy apk(AppUpdateRelease release) {
            return new RedirectPolicy(false, release.metadataUrl());
        }

        boolean allows(HttpUrl from, HttpUrl to) {
            if (!to.isHttps() || !hasNoUserInfoOrFragment(to)) {
                return false;
            }
            if (metadata) {
                return sameOrigin(metadataOrigin, to) && to.encodedQuery() == null;
            }
            return sameOrigin(metadataOrigin, to) && to.encodedQuery() == null;
        }
    }

    private final class Job {
        private final long generation;
        private final ActiveCall calls = new ActiveCall();
        private volatile boolean cancelled;

        Job(long generation) {
            this.generation = generation;
        }

        boolean isCancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
            calls.cancel();
        }

        void clearCall() {
            calls.clear();
        }

        void clearCall(Call old) {
            calls.clear(old);
        }
    }

    /** Holds the request through body consumption so cancellation still reaches OkHttp. */
    static final class ActiveCall {
        private Call current;
        private boolean cancelled;

        synchronized void set(Call call) {
            current = call;
            if (cancelled) {
                call.cancel();
            }
        }

        synchronized void clear(Call call) {
            if (current == call) {
                current = null;
            }
        }

        synchronized void clear() {
            current = null;
        }

        void cancel() {
            Call call;
            synchronized (this) {
                cancelled = true;
                call = current;
            }
            if (call != null) {
                call.cancel();
            }
        }
    }

    /** Executes a call but deliberately retains it until its response body has been closed. */
    static Response executeAndRetainCall(ActiveCall activeCall, Call call) throws IOException {
        activeCall.set(call);
        try {
            return call.execute();
        } catch (IOException | RuntimeException failure) {
            activeCall.clear(call);
            throw failure;
        }
    }

    private interface JobWork {
        void run(Job job) throws IOException;
    }

    private interface ErrorCallback {
        void onError(String message);
    }

    private interface ProgressCallback {
        void onProgress(long downloaded, long total);
    }

    private static final class UpdateFailure extends IOException {
        UpdateFailure(String message) {
            super(message);
        }
    }
}
