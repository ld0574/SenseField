package com.openkhub.sensefield;

import okhttp3.HttpUrl;

/** Immutable, validated metadata for one application update. */
public final class AppUpdateRelease {
    public static final long MAX_APK_BYTES = 100L * 1024L * 1024L;

    enum Source {
        GITHUB_RELEASE,
        SELF_HOSTED
    }

    private final String packageName;
    private final String versionName;
    private final long versionCode;
    private final HttpUrl apkUrl;
    private final long bytes;
    private final String sha256;
    private final String notes;
    private final Source source;
    private final HttpUrl metadataUrl;
    private final String githubTag;

    AppUpdateRelease(String packageName, String versionName, long versionCode,
                     HttpUrl apkUrl, long bytes, String sha256, String notes,
                     Source source, HttpUrl metadataUrl, String githubTag) {
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.apkUrl = apkUrl;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.notes = notes == null ? "" : notes;
        this.source = source;
        this.metadataUrl = metadataUrl;
        this.githubTag = githubTag;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getVersionName() {
        return versionName;
    }

    /** Returns -1 when the version code is not known until the APK is inspected. */
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

    Source source() {
        return source;
    }

    HttpUrl metadataUrl() {
        return metadataUrl;
    }

    String githubTag() {
        return githubTag;
    }
}
