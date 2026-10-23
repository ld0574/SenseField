package com.openkhub.sensefield;

import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseDownloadPolicyTest {
    static final URI RELEASE = URI.create("https://gitee.com/leda/SenseField/releases/download/0.4.1/sensefieldv0.4.1.apk");
    @Test public void projectReleaseFollowsObservedRoutesWithSignedQuery() {
        assertTrue(ReleaseDownloadPolicy.artifactRedirect(RELEASE, URI.create(
                "https://gitee.com/leda/SenseField/attach_files/3329037/download/sensefieldv0.4.1.apk")));
        assertTrue(ReleaseDownloadPolicy.artifactRedirect(RELEASE, URI.create(
                "https://foruda.gitee.com/attach_file/1791287473515342172/sensefieldv0.4.1.apk?token=fixture")));
        for (String url : new String[]{
                "http://foruda.gitee.com/attach_file/123/sensefieldv0.4.1.apk",
                "https://foruda.gitee.com.evil.test/attach_file/123/sensefieldv0.4.1.apk",
                "https://user@foruda.gitee.com/attach_file/123/sensefieldv0.4.1.apk",
                "https://foruda.gitee.com:8443/attach_file/123/sensefieldv0.4.1.apk",
                "https://foruda.gitee.com/attach_file/123/other.apk",
                "https://gitee.com/other/SenseField/attach_files/123/download/sensefieldv0.4.1.apk",
                "https://gitee.com/leda/SenseField/attach_files/123/download/sensefieldv0.4.1.apk?token=fixture",
                "https://foruda.gitee.com/unrelated/sensefieldv0.4.1.apk"})
            assertFalse(url, ReleaseDownloadPolicy.artifactRedirect(RELEASE, URI.create(url)));
    }
    @Test public void crossOriginIsOnlyForOwnedIndexAndThisProject() {
        URI index = URI.create("https://888413.xyz/apk/latest.json");
        assertTrue(ReleaseDownloadPolicy.apkFromManifest(index, RELEASE));
        assertFalse(ReleaseDownloadPolicy.apkFromManifest(URI.create("https://elsewhere.test/latest.json"), RELEASE));
        assertFalse(ReleaseDownloadPolicy.apkFromManifest(URI.create("https://888413.xyz/other/latest.json"), RELEASE));
        assertFalse(ReleaseDownloadPolicy.apkFromManifest(index, URI.create(RELEASE.toString().replace("/leda/", "/other/"))));
        assertFalse(ReleaseDownloadPolicy.apkFromManifest(index, URI.create(RELEASE + "?token=fixture")));
        assertFalse(ReleaseDownloadPolicy.apkFromManifest(index, URI.create("https://foruda.gitee.com/attach_file/123/app.apk")));
        assertFalse(ReleaseDownloadPolicy.artifactRedirect(index, URI.create("https://foruda.gitee.com/attach_file/123/app.apk")));
    }
}
