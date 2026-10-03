package com.openkhub.sensefield;

import org.json.JSONArray;
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
    private static final HttpUrl GITHUB_METADATA = HttpUrl.get(AppUpdateClient.DEFAULT_METADATA_URL);
    private static final HttpUrl SELF_HOSTED_METADATA =
            HttpUrl.get("https://updates.example.test/android/manifest.json");
    private static final byte[] APK_FIXTURE = "apk!".getBytes(StandardCharsets.UTF_8);
    private static final String APK_HASH = sha256(APK_FIXTURE);

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void numericSemverComparesComponentsNumericallyAndRejectsNonSemver() throws Exception {
        assertTrue(AppUpdateClient.compareNumericVersions("0.10.0", "0.9.9") > 0);
        assertEquals(0, AppUpdateClient.compareNumericVersions("12.2.3", "12.2.3"));
        assertTrue(AppUpdateClient.compareNumericVersions("0.4.1", "0.4.0") > 0);
        assertThrows(java.io.IOException.class,
                () -> AppUpdateClient.compareNumericVersions("v0.4.1", "0.4.0"));
        assertThrows(java.io.IOException.class,
                () -> AppUpdateClient.compareNumericVersions("0.04.1", "0.4.0"));
        assertThrows(java.io.IOException.class,
                () -> AppUpdateClient.compareNumericVersions("0.4.1-beta", "0.4.0"));
        assertThrows(java.io.IOException.class,
                () -> AppUpdateClient.compareNumericVersions("1234567890.0.1", "0.0.1"));
    }

    @Test
    public void githubStableReleaseReturnsOnlyNewerVersionAndUnknownVersionCode() throws Exception {
        AppUpdateRelease release = AppUpdateClient.parseGithubRelease(githubRelease(),
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA);

        assertNotNull(release);
        assertEquals("0.4.1", release.getVersionName());
        assertEquals(-1, release.getVersionCode());
        assertEquals(4L, release.getBytes());
        assertEquals(APK_HASH, release.getSha256());
        assertEquals("Small fix", release.getNotes());
        assertEquals("https://github.com/ld0574/SenseField/releases/download/v0.4.1/sensefield.apk",
                release.getApkUrl());
        assertNull(AppUpdateClient.parseGithubRelease(githubRelease(), "0.4.1",
                PACKAGE_NAME, GITHUB_METADATA));
    }

    @Test
    public void olderStableGithubReleaseIsNoUpdateEvenWithoutApkMetadata() throws Exception {
        JSONObject oldRelease = githubRelease()
                .put("tag_name", "v0.3.9")
                .put("assets", new JSONArray());

        assertNull(AppUpdateClient.parseGithubRelease(oldRelease, "0.4.0",
                PACKAGE_NAME, GITHUB_METADATA));

        JSONObject missingDigest = githubRelease().put("tag_name", "v0.3.9");
        missingDigest.getJSONArray("assets").getJSONObject(0).remove("digest");
        assertNull(AppUpdateClient.parseGithubRelease(missingDigest, "0.4.0",
                PACKAGE_NAME, GITHUB_METADATA));
    }

    @Test
    public void githubReleaseRejectsDraftPrereleaseDuplicateApkAndMissingDigest() throws Exception {
        JSONObject draft = githubRelease();
        draft.put("draft", true);
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(draft,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));

        JSONObject prerelease = githubRelease();
        prerelease.put("prerelease", true);
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(prerelease,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));

        JSONObject duplicate = githubRelease();
        JSONObject secondApk = duplicate.getJSONArray("assets").getJSONObject(0);
        duplicate.getJSONArray("assets").put(new JSONObject(secondApk.toString())
                .put("name", "second.apk"));
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(duplicate,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));

        JSONObject noDigest = githubRelease();
        noDigest.getJSONArray("assets").getJSONObject(0).remove("digest");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(noDigest,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));
    }

    @Test
    public void githubReleaseRejectsOutOfRepositoryAndMalformedPackageUrls() throws Exception {
        JSONObject outsideHost = githubRelease();
        outsideHost.getJSONArray("assets").getJSONObject(0)
                .put("browser_download_url", "https://evil.example/sensefield.apk");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(outsideHost,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));

        JSONObject wrongPath = githubRelease();
        wrongPath.getJSONArray("assets").getJSONObject(0).put("browser_download_url",
                "https://github.com/another/repo/releases/download/v0.4.1/sensefield.apk");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(wrongPath,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));

        JSONObject queryCredential = githubRelease();
        queryCredential.getJSONArray("assets").getJSONObject(0).put("browser_download_url",
                "https://github.com/ld0574/SenseField/releases/download/v0.4.1/sensefield.apk?token=secret");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(queryCredential,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));

        JSONObject malformedVersion = githubRelease();
        malformedVersion.put("tag_name", "v0.4.1-beta");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(malformedVersion,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));
    }

    @Test
    public void githubReleaseRejectsMissingAndOversizedAssets() throws Exception {
        JSONObject missingApk = githubRelease();
        missingApk.put("assets", new JSONArray());
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(missingApk,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));

        JSONObject oversized = githubRelease();
        oversized.getJSONArray("assets").getJSONObject(0)
                .put("size", AppUpdateRelease.MAX_APK_BYTES + 1);
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseGithubRelease(oversized,
                "0.4.0", PACKAGE_NAME, GITHUB_METADATA));
    }

    @Test
    public void selfHostedManifestRequiresSamePackageNewCodeAndSameHttpsOrigin() throws Exception {
        AppUpdateRelease release = AppUpdateClient.parseSelfHostedManifest(manifest(), 17,
                PACKAGE_NAME, SELF_HOSTED_METADATA);
        assertNotNull(release);
        assertEquals("0.4.1", release.getVersionName());
        assertEquals(18, release.getVersionCode());
        assertEquals(4L, release.getBytes());
        assertNull(AppUpdateClient.parseSelfHostedManifest(manifest(), 18,
                PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject differentPackage = manifest();
        differentPackage.put("package_name", "com.attacker.app");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                differentPackage, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

        JSONObject otherOrigin = manifest();
        otherOrigin.put("apk_url", "https://attacker.example/sensefield.apk");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                otherOrigin, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

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
    public void selfHostedManifestRejectsMalformedVersionAndSize() throws Exception {
        JSONObject malformedVersion = manifest();
        malformedVersion.put("version_name", "0.04.1");
        assertThrows(java.io.IOException.class, () -> AppUpdateClient.parseSelfHostedManifest(
                malformedVersion, 17, PACKAGE_NAME, SELF_HOSTED_METADATA));

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
                () -> AppUpdateClient.validateMetadataUrl("http://updates.example.test/manifest.json"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateClient.validateMetadataUrl("https://user:secret@updates.example.test/manifest.json"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateClient.validateMetadataUrl("https://updates.example.test/manifest.json?token=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUpdateClient.validateMetadataUrl("https://updates.example.test/manifest.json#latest"));
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

    private static JSONObject githubRelease() throws Exception {
        JSONObject asset = new JSONObject()
                .put("name", "sensefield.apk")
                .put("size", APK_FIXTURE.length)
                .put("digest", "sha256:" + APK_HASH)
                .put("browser_download_url",
                        "https://github.com/ld0574/SenseField/releases/download/v0.4.1/sensefield.apk");
        return new JSONObject()
                .put("draft", false)
                .put("prerelease", false)
                .put("tag_name", "v0.4.1")
                .put("body", "Small fix")
                .put("assets", new JSONArray().put(asset));
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
