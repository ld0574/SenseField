package com.openkhub.sensefield;

import static org.junit.Assert.*;

import okhttp3.HttpUrl;
import org.junit.Test;

public class AppUpdatePackageVerifierTest {
    private static AppUpdateRelease release(String name, long code) {
        return new AppUpdateRelease("com.openkhub.sensefield", name, code,
                HttpUrl.get("https://gitee.com/leda/SenseField/releases/download/0.4.8/sensefieldv0.4.8.apk"),
                100, "a".repeat(64), "",
                HttpUrl.get("https://888413.xyz/apk/latest.json"));
    }

    @Test public void higherCodeAcceptsCorrectedNameAfterMisnumberedPreview() {
        assertTrue(AppUpdatePackageVerifier.verifyVersionIdentity(release("0.4.8", 28),
                "0.4.8", 28, "0.4.9", 26).valid);
    }

    @Test public void higherDisplayVersionDoesNotPermitCodeDowngrade() {
        assertFalse(AppUpdatePackageVerifier.verifyVersionIdentity(release("0.5.0", 27),
                "0.5.0", 27, "0.4.8", 28).valid);
    }

    @Test public void sameCodeAndNameProceedToHashAndSignatureChecks() {
        assertTrue(AppUpdatePackageVerifier.verifyVersionIdentity(release("0.4.8", 28),
                "0.4.8", 28, "0.4.8", 28).valid);
    }

    @Test public void sameCodeWithDifferentNameIsRejected() {
        assertFalse(AppUpdatePackageVerifier.verifyVersionIdentity(release("0.4.8", 28),
                "0.4.8", 28, "0.4.9", 28).valid);
    }

    @Test public void archiveMustMatchManifestName() {
        assertFalse(AppUpdatePackageVerifier.verifyVersionIdentity(release("0.4.8", 28),
                "0.4.9", 28, "0.4.7", 24).valid);
    }

    @Test public void archiveMustMatchManifestCode() {
        assertFalse(AppUpdatePackageVerifier.verifyVersionIdentity(release("0.4.8", 28),
                "0.4.8", 29, "0.4.7", 24).valid);
    }
}
