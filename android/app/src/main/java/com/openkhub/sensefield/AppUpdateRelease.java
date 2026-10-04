package com.openkhub.sensefield;

import okhttp3.HttpUrl;

/** Immutable, validated metadata for one application update. */
public final class AppUpdateRelease {
    // The offline voice model makes the current APK about 214 MB.
    public static final long MAX_APK_BYTES = 256L * 1024L * 1024L;

    private final String packageName;
    private final String versionName;
    private final long versionCode;
    private final HttpUrl apkUrl;
    private final long bytes;
    private final String sha256;
    private final String notes;
    private final HttpUrl metadataUrl;

    AppUpdateRelease(String packageName, String versionName, long versionCode,
                     HttpUrl apkUrl, long bytes, String sha256, String notes,
                     HttpUrl metadataUrl) {
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.apkUrl = apkUrl;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.notes = notes == null ? "" : notes;
        this.metadataUrl = metadataUrl;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getVersionName() {
        return versionName;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public String getApkUrl() {
        return apkUrl.toString();
    }

    HttpUrl apkHttpUrl() {
        return apkUrl;
    }

    public long getBytes() {
        return bytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getNotes() {
        return notes;
    }

    HttpUrl metadataUrl() {
        return metadataUrl;
    }
}
