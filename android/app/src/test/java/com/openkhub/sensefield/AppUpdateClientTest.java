package com.openkhub.sensefield;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AppUpdateClientTest {
    private static final String PACKAGE_NAME = "com.openkhub.sensefield";
    private static final HttpUrl SELF_HOSTED_METADATA =
            HttpUrl.get("https://updates.example.test/android/latest.json");
    private static final byte[] APK_FIXTURE = "apk!".getBytes(StandardCharsets.UTF_8);
    private static final String APK_HASH = sha256(APK_FIXTURE);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void manifestReturnsOnlyVersionCodesNewerThanTheInstalledApp() throws Exception {
        AppUpdateRelease release = AppUpdateClient.parseSelfHostedManifest(manifest(), 17,
                PACKAGE_NAME, SELF_HOSTED_METADATA);

        assertNotNull(release);
        assertEquals("0.4.1", release.getVersionName());
        assertEquals(18, release.getVersionCode());
        assertEquals(4L, release.getBytes());
        assertEquals(APK_HASH, release.getSha256());
        assertEquals("Small fix", release.getNotes());
        assertEquals("https://updates.example.test/android/sensefield.apk", release.getApkUrl());
        AppUpdateRelease sameBuild = AppUpdateClient.parseSelfHostedManifest(manifest(), 18,
                PACKAGE_NAME, SELF_HOSTED_METADATA);
        assertNotNull("same-code releases must reach the installed APK hash check", sameBuild);
        assertTrue(AppUpdateClient.isSameVersionNoOp(sameBuild, "0.4.1", 18, APK_HASH));
        assertFalse(AppUpdateClient.isSameVersionNoOp(sameBuild, "0.4.1", 18,
                "0".repeat(64)));
        assertThrows(java.io.IOException.class,
                () -> AppUpdateClient.isSameVersionNoOp(sameBuild, "0.4.0", 18, APK_HASH));
        assertNull(AppUpdateClient.parseSelfHostedManifest(
                manifest().put("version_code", 17), 18, PACKAGE_NAME, SELF_HOSTED_METADATA));
    }

    @Test public void newerInstalledAppIgnoresOlderGiteeIndexButStillValidatesIt() throws Exception {
        HttpUrl index = HttpUrl.get("https://888413.xyz/apk/latest.json");
        JSONObject previous = manifest().put("apk_url",
                "https://gitee.com/leda/SenseField/releases/download/0.4.1/sensefieldv0.4.1.apk");
        assertNull(AppUpdateClient.parseSelfHostedManifest(previous, 19, PACKAGE_NAME, index));
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                previous.put("apk_sha256", "invalid"), 19, PACKAGE_NAME, index));
    }

    @Test
    public void offlineVoiceApkCanBeOfferedWithoutRemovingTheDownloadSizeLimit() throws Exception {
        JSONObject voicePackage = manifest().put("apk_bytes", 214121822L);
        AppUpdateRelease release = AppUpdateClient.parseSelfHostedManifest(voicePackage, 18,
                PACKAGE_NAME, SELF_HOSTED_METADATA);
        assertNotNull(release);
        assertEquals(214121822L, release.getBytes());
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                voicePackage.put("apk_bytes", 256L * 1024L * 1024L + 1),
                18, PACKAGE_NAME, SELF_HOSTED_METADATA));
    }

    @Test
    public void manifestRejectsDifferentPackageAndNonSameOriginApkUrls() throws Exception {
        JSONObject differentPackage = manifest();
        differentPackage.put("package_name", "com.attacker.app");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                differentPackage, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject otherOrigin = manifest();
        otherOrigin.put("apk_url", "https://cdn.example.test/sensefield.apk");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                otherOrigin, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject cleartext = manifest();
        cleartext.put("apk_url", "http://updates.example.test/android/sensefield.apk");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                cleartext, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject urlCredential = manifest();
        urlCredential.put("apk_url", "https://user:secret@updates.example.test/sensefield.apk");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                urlCredential, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject queryCredential = manifest();
        queryCredential.put("apk_url", "https://updates.example.test/sensefield.apk?token=secret");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                queryCredential, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));
    }

    @Test
    public void ownedIndexAcceptsProjectGiteeApkWhileOtherOriginsStayRejected() throws Exception {
        JSONObject json = manifest().put("apk_url",
                "https://gitee.com/leda/SenseField/releases/download/0.4.1/sensefieldv0.4.1.apk");
        assertNotNull(AppUpdateClient.parseSelfHostedManifest(json, 18, PACKAGE_NAME,
                HttpUrl.get("https://888413.xyz/apk/latest.json")));
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(json,
                18, PACKAGE_NAME, SELF_HOSTED_METADATA));
    }

    @Test
    public void manifestRejectsMalformedVersionAndUnsafeMetadata() throws Exception {
        JSONObject malformedVersion = manifest();
        malformedVersion.put("version_name", "0.04.1");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                malformedVersion, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject invalidCode = manifest();
        invalidCode.put("version_code", 0);
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                invalidCode, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject tooLarge = manifest();
        tooLarge.put("apk_bytes", AppUpdateRelease.MAX_APK_BYTES + 1);
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                tooLarge, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject badHash = manifest();
        badHash.put("apk_sha256", "not-a-digest");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                badHash, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject unsupportedSchema = manifest();
        unsupportedSchema.put("schema_version", 2);
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                unsupportedSchema, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject invalidNotes = manifest();
        invalidNotes.put("release_notes", 123);
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                invalidNotes, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));
    }

    @Test
    public void metadataUrlRejectsCleartextCredentialsQueriesAndFragments() {
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateClient.validateMetadataUrl("http://updates.example.test/latest.json"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateClient.validateMetadataUrl("https://user:secret@updates.example.test/latest.json"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateClient.validateMetadataUrl("https://updates.example.test/latest.json?token=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateClient.validateMetadataUrl("https://updates.example.test/latest.json#latest"));
    }

    @Test
    public void downloadedArtifactRequiresExactSizeAndSha256() throws Exception {
        File apk = temporaryFolder.newFile("sample.apk");
        try (FileOutputStream output = new FileOutputStream(apk)) {
            output.write(APK_FIXTURE);
        }

        AppUpdateClient.verifyDownloadedArtifact(apk, APK_FIXTURE.length, APK_HASH);
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.verifyDownloadedArtifact(
                apk, APK_FIXTURE.length + 1, APK_HASH));
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.verifyDownloadedArtifact(
                apk, APK_FIXTURE.length, "0".repeat(64)));
    }

    @Test
    public void eachDownloadGetsItsOwnTemporaryPartFile() throws Exception {
        File updateDirectory = temporaryFolder.newFolder("updates");

        File first = AppUpdateClient.createPartFile(updateDirectory);
        File second = AppUpdateClient.createPartFile(updateDirectory);

        assertNotEquals(first.getAbsolutePath(), second.getAbsolutePath());
        assertEquals(updateDirectory.getCanonicalFile(), first.getParentFile().getCanonicalFile());
        assertEquals(updateDirectory.getCanonicalFile(), second.getParentFile().getCanonicalFile());
        assertTrue(first.getName().endsWith(".part"));
        assertTrue(second.getName().endsWith(".part"));
    }

    @Test
    public void abandonedPartFilesAreCleanedWithoutTouchingActiveOrUnrelatedFiles() throws Exception {
        File updateDirectory = temporaryFolder.newFolder("updates-stale");
        File stalePart = AppUpdateClient.createPartFile(updateDirectory);
        File activePart = AppUpdateClient.createPartFile(updateDirectory);
        File unrelated = new File(updateDirectory, "notes.txt");
        assertTrue(unrelated.createNewFile());
        long now = System.currentTimeMillis();
        assertTrue(stalePart.setLastModified(now - 2L * 60L * 60L * 1000L));

        AppUpdateClient.cleanupStalePartFiles(updateDirectory, now);

        assertFalse(stalePart.exists());
        assertTrue(activePart.exists());
        assertTrue(unrelated.exists());
    }

    private static JSONObject manifest() throws Exception {
        return new JSONObject()
                .put("schema_version", 1)
                .put("package_name", PACKAGE_NAME)
                .put("version_name", "0.4.1")
                .put("version_code", 18)
                .put("apk_url", "https://updates.example.test/android/sensefield.apk")
                .put("apk_bytes", APK_FIXTURE.length)
                .put("apk_sha256", APK_HASH)
                .put("release_notes", "Small fix");
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) {
                hex.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
