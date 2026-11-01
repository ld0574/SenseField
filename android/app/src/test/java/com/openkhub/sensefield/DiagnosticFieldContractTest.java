package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Test;

/**
 * Contract tests for the diagnostic privacy surface.
 *
 * <p>These do not try the Android runtime; they pin the three decisions an audit
 * found worth protecting: which metadata keys may ship, that screenshots stay
 * opt-in, and that the privacy statement travels with the export. The key list
 * below is deliberately a second, hand-written copy — a change to
 * {@code DiagnosticPrivacySurface.METADATA_FIELDS} must fail here so a new field is
 * reviewed instead of slipping into a package.</p>
 */
public final class DiagnosticFieldContractTest {

    /** Every key the recorder may write; mirrors DiagnosticPrivacySurface.METADATA_FIELDS. */
    private static final List<String> REVIEWED_METADATA_FIELDS = Arrays.asList(
            "schema", "schema_version", "game_id", "game_name", "session_id",
            "started_elapsed_ms", "started_wall_ms", "model", "app_version",
            "manufacturer", "brand", "product", "android_release", "sdk",
            "screen_width_px", "screen_height_px", "density_dpi",
            "images_enabled", "portrait_images_allowed", "image_period_ms",
            "match3_element_family_limit", "match3_element_cover_reserved_families",
            "match3_element_samples_per_family", "match3_element_thumbnail_px",
            "match3_element_scope", "context_sample_period_ms", "context_pre_ms",
            "context_post_ms", "context_max_post_ms", "context_auto_gap_ms",
            "context_max_frames", "context_frame_bytes_limit", "context_screen_edge",
            "context_map_edge", "context_resolution_policy", "image_jobs_limit",
            "image_job_bytes_limit", "image_job_budget_scope", "image_duration_limit_ms",
            "image_bytes_limit", "time_note", "image_note", "resolution_note",
            "version_name", "version_code",
            "match3_catalog_id", "match3_gallery_id", "match3_gallery_sha256",
            "match3_gallery_status", "match3_cover_catalog_id",
            "match3_cover_catalog_sha256", "match3_cover_catalog_status",
            "match3_board_feedback_id", "match3_board_feedback_sha256",
            "match3_board_feedback_status", "match3_hud_glyph_catalog_id",
            "match3_hud_glyph_catalog_sha256", "match3_hud_glyph_catalog_status",
            "match3_gallery_goals_pending_review");

    @Test public void metadataFieldsMatchTheReviewedList() {
        assertEquals(new ArrayList<>(REVIEWED_METADATA_FIELDS),
                new ArrayList<>(DiagnosticPrivacySurface.METADATA_FIELDS));
    }

    @Test public void metadataFieldsCarryNoUniqueIdentifier() {
        List<String> forbidden = Arrays.asList(
                "android_id", "imei", "meid", "serial", "mac", "mac_address",
                "phone", "phone_number", "email", "advertising_id", "gaid");
        for (String key : DiagnosticPrivacySurface.METADATA_FIELDS) {
            String lower = key.toLowerCase(java.util.Locale.US);
            for (String banned : forbidden)
                assertFalse("metadata must not carry " + banned + ": " + key,
                        lower.equals(banned) || lower.endsWith("_" + banned));
        }
    }

    @Test public void screenshotsAreOptIn() {
        assertFalse("never chosen means no screenshots",
                DiagnosticPrivacySurface.imagesEnabled(false, false));
        assertFalse("a stale true under a missing key must not enable capture",
                DiagnosticPrivacySurface.imagesEnabled(false, true));
        assertFalse("explicitly off stays off",
                DiagnosticPrivacySurface.imagesEnabled(true, false));
        assertTrue("only an explicit on enables capture",
                DiagnosticPrivacySurface.imagesEnabled(true, true));
    }

    @Test public void privacyNoticeShipsWithTheExport() throws Exception {
        File root = Files.createTempDirectory("sensefield-privacy-export").toFile();
        try {
            String id = "diag-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
            try (DiagnosticArchive archive = new DiagnosticArchive(root, id, "{}")) {
                File zip = new File(root, "exports/privacy.zip");
                DiagnosticArchive.export(archive.directory, zip, "");
                try (ZipFile opened = new ZipFile(zip)) {
                    ZipEntry notice = opened.getEntry(DiagnosticPrivacyNotice.FILE_NAME);
                    assertTrue("export must carry " + DiagnosticPrivacyNotice.FILE_NAME,
                            notice != null);
                    String text = new String(opened.getInputStream(notice).readAllBytes(),
                            StandardCharsets.UTF_8);
                    assertTrue(text.contains("不含录音"));
                    assertTrue(text.contains("不含语音识别"));
                    assertTrue(text.contains("ANDROID_ID"));
                    assertTrue(text.contains("不会自动上传"));
                }
            }
        } finally {
            DiagnosticArchive.delete(root);
        }
    }
}