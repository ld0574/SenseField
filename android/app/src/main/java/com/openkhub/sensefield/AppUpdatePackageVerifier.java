package com.openkhub.sensefield;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Checks a downloaded APK against the installed application before offering it to Android. */
public final class AppUpdatePackageVerifier {
    private AppUpdatePackageVerifier() {
    }

    /** Immutable verification result. Messages are safe to show directly to the user. */
    public static final class Result {
        public final boolean valid;
        public final String message;

        private Result(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }
    }

    public static Result verify(Context context, File apkFile, AppUpdateRelease release) {
        if (context == null || apkFile == null || release == null) {
            return invalid("无法检查更新安装包。");
        }
        if (!release.getPackageName().equals(context.getPackageName())) {
            return invalid("该更新不适用于当前应用。");
        }
        try {
            AppUpdateClient.verifyDownloadedArtifact(apkFile, release.getBytes(),
                    release.getSha256());
        } catch (IOException invalidArtifact) {
            return invalid("更新安装包的大小或校验值不正确。");
        }

        PackageManager packageManager = context.getPackageManager();
        final PackageInfo archive;
        final PackageInfo installed;
        try {
            archive = packageManager.getPackageArchiveInfo(apkFile.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            installed = packageManager.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException | SecurityException unavailable) {
            return invalid("Android 无法检查当前应用的签名信息。");
        }
        if (archive == null || archive.signingInfo == null) {
            return invalid("Android 无法验证更新安装包的签名。");
        }
        if (installed == null || installed.signingInfo == null) {
            return invalid("Android 无法验证当前应用的签名。");
        }
        if (!release.getPackageName().equals(archive.packageName)) {
            return invalid("更新安装包名称与当前应用不一致。");
        }
        if (archive.versionName == null || !release.getVersionName().equals(archive.versionName)) {
            return invalid("更新安装包版本名称与发布信息不一致。");
        }

        long archiveCode = archive.getLongVersionCode();
        long installedCode = installed.getLongVersionCode();
        if (archiveCode <= installedCode) {
            return invalid("更新安装包版本不高于当前已安装版本。");
        }
        if (release.getVersionCode() > 0 && archiveCode != release.getVersionCode()) {
            return invalid("更新安装包版本号与发布信息不一致。");
        }
        try {
            if (AppUpdateClient.compareNumericVersions(archive.versionName,
                    installed.versionName) < 0) {
                return invalid("此更新会降低应用版本，已阻止安装。");
            }
        } catch (IOException invalidInstalledVersion) {
            return invalid("无法安全检查当前应用版本。");
        }

        Set<String> archiveCertificates = signerDigests(archive.signingInfo);
        Set<String> installedCertificates = signerDigests(installed.signingInfo);
        if (archiveCertificates == null || archiveCertificates.isEmpty()
                || installedCertificates == null || installedCertificates.isEmpty()) {
            return invalid("Android 无法读取应用签名证书。");
        }
        if (!archiveCertificates.equals(installedCertificates)) {
            return invalid("更新安装包使用了不同的应用签名证书。");
        }
        return new Result(true, "更新安装包已验证，可以交由 Android 安装器处理。");
    }

    private static Result invalid(String message) {
        return new Result(false, message);
    }

    private static Set<String> signerDigests(SigningInfo signingInfo) {
        if (signingInfo == null) {
            return null;
        }
        Signature[] signatures = signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        Set<String> digests = new TreeSet<>();
        try {
            for (Signature signature : signatures) {
                if (signature == null) {
                    return null;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digests.add(toHex(digest.digest(signature.toByteArray())));
            }
        } catch (NoSuchAlgorithmException impossible) {
            return null;
        }
        return digests;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
