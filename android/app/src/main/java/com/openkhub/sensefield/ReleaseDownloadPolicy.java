package com.openkhub.sensefield;

import java.net.URI;

/** Explicit project Release routes, including Gitee's observed signed file host. */
final class ReleaseDownloadPolicy {
    private ReleaseDownloadPolicy() {}

    private static boolean https(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getFragment() == null;
    }

    static boolean sameOrigin(URI left, URI right) {
        return https(left) && https(right) && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }

    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }

    static boolean projectRelease(URI uri) {
        return https(uri) && port(uri) == 443 && "gitee.com".equalsIgnoreCase(uri.getHost())
                && uri.getRawQuery() == null && uri.getRawPath() != null
                && uri.getRawPath().matches("/leda/SenseField/releases/download/"
                        + "[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    static boolean apkFromManifest(URI manifest, URI apk) {
        if (!https(apk) || apk.getRawQuery() != null) return false;
        if (sameOrigin(manifest, apk)) return true;
        // Keep a stable, tiny index on the existing host; large files use this
        // one project's Release. Other manifests do not inherit this exception.
        return https(manifest) && port(manifest) == 443
                && "888413.xyz".equalsIgnoreCase(manifest.getHost())
                && "/apk/latest.json".equals(manifest.getRawPath())
                && manifest.getRawQuery() == null && projectRelease(apk)
                && apk.getRawPath().endsWith(".apk");
    }

    static boolean artifactRedirect(URI original, URI next) {
        if (!projectRelease(original))
            return sameOrigin(original, next) && next.getRawQuery() == null;
        if (!https(next) || port(next) != 443 || next.getRawPath() == null) return false;
        String file = original.getRawPath().substring(original.getRawPath().lastIndexOf('/') + 1);
        String path = next.getRawPath();
        if ("gitee.com".equalsIgnoreCase(next.getHost())) {
            return next.getRawQuery() == null && (original.getRawPath().equals(path)
                    || path.matches("/leda/SenseField/attach_files/[0-9]+/download/"
                            + java.util.regex.Pattern.quote(file)));
        }
        // Query parameters are required for Gitee's short-lived signed URL.
        // Never persist that URL; retries start at the pinned Release route.
        return "foruda.gitee.com".equalsIgnoreCase(next.getHost())
                && path.matches("/attach_file/[0-9]+/" + java.util.regex.Pattern.quote(file));
    }
}
