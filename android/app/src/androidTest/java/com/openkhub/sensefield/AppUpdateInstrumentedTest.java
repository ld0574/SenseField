package com.openkhub.sensefield;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android archive/signature/provider checks; live HTTPS uses an explicit temporary fixture. */
@RunWith(AndroidJUnit4.class)
public class AppUpdateInstrumentedTest {
    private Context context;
    private AppUpdateClient client;
    private File localFixture;

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After public void cleanup() {
        if (client != null) client.close();
        if (localFixture != null) localFixture.delete();
    }

    @Test public void realHttpsDownloadsSignedUpgradeAndProvidesInstallerReadableUri() throws Exception {
        assumeTrue("Temporary HTTPS upgrade fixture is not enabled",
                BuildConfig.APP_UPDATE_METADATA_URL.equals("https://10.0.2.2:18766/latest.json"));
        client = new AppUpdateClient(context, BuildConfig.APP_UPDATE_METADATA_URL);
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<AppUpdateRelease> release = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        client.check(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, new AppUpdateClient.CheckCallback() {
            @Override public void onUpdate(AppUpdateRelease value) { release.set(value); checked.countDown(); }
            @Override public void onNoUpdate() { failure.set("Expected a future test release"); checked.countDown(); }
            @Override public void onError(String message) { failure.set(message); checked.countDown(); }
        });
        assertTrue("Metadata callback timed out", checked.await(25, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertNotNull(release.get());
        assertTrue("manifest version must not be older than the installed app",
                release.get().getVersionCode() >= BuildConfig.VERSION_CODE);
        if (release.get().getVersionCode() == BuildConfig.VERSION_CODE) {
            assertEquals(BuildConfig.VERSION_NAME, release.get().getVersionName());
        }
        CountDownLatch downloaded = new CountDownLatch(1);
        AtomicReference<File> artifact = new AtomicReference<>();
        client.download(release.get(), new AppUpdateClient.DownloadCallback() {
            @Override public void onProgress(long bytes, long total) { }
            @Override public void onDownloaded(File value) { artifact.set(value); downloaded.countDown(); }
            @Override public void onError(String message) { failure.set(message); downloaded.countDown(); }
        });
        assertTrue("APK download timed out", downloaded.await(90, TimeUnit.SECONDS));
        assertNull(failure.get());
        localFixture = artifact.get();
        assertNotNull(localFixture);
        assertEquals(new File(context.getFilesDir(), "updates").getCanonicalPath(),
                localFixture.getParentFile().getCanonicalPath());
        AppUpdatePackageVerifier.Result result = AppUpdatePackageVerifier.verify(context, localFixture, release.get());
        assertTrue(result.message, result.valid);
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", localFixture);
        assertEquals("content", uri.getScheme());
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            assertNotNull(stream);
            assertEquals('P', stream.read());
            assertEquals('K', stream.read());
        }
        Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertNotNull("Android installer must resolve the URI", install.resolveActivity(context.getPackageManager()));
        // A changed byte must be rejected before archive parsing or installer handoff.
        try (java.io.RandomAccessFile changed = new java.io.RandomAccessFile(localFixture, "rw")) {
            changed.seek(10); int original = changed.read();
            changed.seek(10); changed.write(original ^ 255);
        }
        assertFalse(AppUpdatePackageVerifier.verify(context, localFixture, release.get()).valid);
    }

    @Test public void sameInstalledVersionIsRejectedEvenWithCorrectHashAndSignature() throws Exception {
        localFixture = File.createTempFile("same-version-", ".apk", context.getCacheDir());
        Files.copy(new File(context.getApplicationInfo().sourceDir).toPath(), localFixture.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        AppUpdateRelease release = releaseFor(localFixture);
        AppUpdatePackageVerifier.Result result = AppUpdatePackageVerifier.verify(context, localFixture, release);
        assertFalse(result.valid);
        assertTrue(result.message.contains("内容相同"));
    }

    @Test public void sameVersionSignedRevisionWithDifferentBytesIsAccepted() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("sameVersionApk");
        assumeTrue("A same-version signed revision fixture was not supplied",
                path != null && new File(path).isFile());
        File fixture = new File(path);
        AppUpdateRelease release = releaseFor(fixture);
        PackageInfo installed = context.getPackageManager().getPackageInfo(
                context.getPackageName(), 0);
        assertEquals(installed.getLongVersionCode(), release.getVersionCode());
        assertEquals(installed.versionName, release.getVersionName());
        assertNotEquals(AppUpdateClient.installedApkSha256(context), release.getSha256());

        AppUpdatePackageVerifier.Result result = AppUpdatePackageVerifier.verify(context,
                fixture, release);
        assertTrue(result.message, result.valid);
    }

    @Test public void sameVersionRevisionWithDifferentSignatureIsRejected() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString(
                "sameVersionWrongSignatureApk");
        assumeTrue("A same-version wrong-signature fixture was not supplied",
                path != null && new File(path).isFile());
        File fixture = new File(path);
        AppUpdateRelease release = releaseFor(fixture);
        PackageInfo installed = context.getPackageManager().getPackageInfo(
                context.getPackageName(), 0);
        assertEquals(installed.getLongVersionCode(), release.getVersionCode());
        assertEquals(installed.versionName, release.getVersionName());

        AppUpdatePackageVerifier.Result result = AppUpdatePackageVerifier.verify(context,
                fixture, release);
        assertFalse(result.valid);
        assertTrue(result.message.contains("不同的应用签名"));
    }

    @Test public void differentSigningCertificateIsRejectedForRealFutureApk() throws Exception {
        File fixture = new File(context.getFilesDir(), "wrong-signature.apk");
        assumeTrue("One-off signing fixture is not configured", fixture.isFile());
        AppUpdatePackageVerifier.Result result = AppUpdatePackageVerifier.verify(context, fixture, releaseFor(fixture));
        assertFalse(result.valid);
        assertTrue(result.message.contains("不同的应用签名"));
    }

    @Test public void updateProviderRefusesUnrelatedPrivateFiles() throws Exception {
        localFixture = File.createTempFile("private-data-", ".txt", context.getFilesDir());
        try {
            FileProvider.getUriForFile(context, context.getPackageName() + ".updates", localFixture);
            fail("Only the updates directory may be shared");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void replacementRefreshesInstalledFingerprintAndBecomesNoOp() throws Exception {
        String expected = InstrumentationRegistry.getArguments().getString("expectedInstalledSha256");
        assumeTrue("Post-install fingerprint fixture was not supplied", expected != null);
        String actual = AppUpdateClient.installedApkSha256(context);
        assertEquals("Cached fingerprint must reflect the replacement APK", expected, actual);
        assertEquals(actual, AppUpdateClient.installedApkSha256(context));
        AppUpdateRelease current = releaseFor(new File(context.getApplicationInfo().sourceDir));
        assertTrue(AppUpdateClient.isSameVersionNoOp(current, BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE, actual));
    }

    private AppUpdateRelease releaseFor(File file) throws Exception {
        PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        assertNotNull(archive);
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = stream.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : hash.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        HttpUrl metadata = HttpUrl.get("https://example.test/latest.json");
        return new AppUpdateRelease(archive.packageName, archive.versionName,
                archive.getLongVersionCode(), HttpUrl.get("https://example.test/app.apk"),
                file.length(), hex.toString(), "", metadata);
    }
}
