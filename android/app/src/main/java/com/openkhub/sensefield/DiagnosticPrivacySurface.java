package com.openkhub.sensefield;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The two privacy decisions that must not drift away from the recorder, kept in a
 * pure-JVM class so a unit test can hold them without loading the Android side of
 * {@link DiagnosticRecorder}.
 *
 * <p>Why here and not in the recorder: the recorder's static initialisation touches
 * executors and image-budget objects, which a plain JVM test should not have to
 * bring up. Everything below is data and arithmetic; nothing imports
 * {@code android.*} or {@code org.json}.</p>
 */
final class DiagnosticPrivacySurface {

    /**
     * Every key the recorder may write into {@code metadata.json}.
     *
     * <p>Declared as data so {@code DiagnosticFieldContractTest} fails on a silent
     * addition: a new key is a new privacy decision (device fact, timestamp or
     * identifier) and has to be reviewed, not merely typed. Conditional keys are
     * listed too, because {@code version_name}/{@code version_code} are written on
     * ordinary devices and the Match3 catalog block on Match3 sessions.</p>
     */
    static final List<String> METADATA_FIELDS = Collections.unmodifiableList(Arrays.asList(
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
            "match3_gallery_goals_pending_review"));

    /**
     * Screenshots are opt-in. A missing key means the player has never chosen, and
     * the recorder then stays text-only: capturing a screen that may hold nicknames,
     * chat lines or notifications is a decision only the player can make.
     */
    static boolean imagesEnabled(boolean keyPresent, boolean storedValue) {
        return keyPresent && storedValue;
    }

    private DiagnosticPrivacySurface() {
    }
}