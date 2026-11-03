package com.openkhub.sensefield.diagnosticshelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Read-only export using Android APIs and the on-disk diagnostic format only. */
public final class DiagnosticExportInstrumentation extends Instrumentation {
    private static final String TARGET = "com.openkhub.sensefield";
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle status = new Bundle();
        try {
            if (arguments == null || !"true".equals(
                    arguments.getString("authorizedDiagnosticsExport"))) {
                throw new SecurityException("Explicit diagnostic export authorization required");
            }
            String requestId = arguments.getString("exportRequestId", "");
            if (!requestId.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Invalid export request ID");
            }
            Context context = getTargetContext();
            if (!TARGET.equals(context.getPackageName())) {
                throw new SecurityException("Unexpected diagnostic target");
            }
            requireStopped(context);
            File external = context.getExternalFilesDir("adb-diagnostics");
            if (external == null) throw new IOException("External export storage unavailable");
            File destination = new File(external, requestId);
            if (destination.exists() || !destination.mkdirs()) {
                throw new IOException("Export request directory must be new");
            }
            File source = new File(context.getFilesDir(), "diagnostics");
            File[] sessions = source.listFiles(file -> file.isDirectory()
                    && !Files.isSymbolicLink(file.toPath())
                    && file.getName().matches("diag-\\d+-[0-9a-fA-F-]+"));
            if (sessions == null) sessions = new File[0];
            Arrays.sort(sessions, Comparator.comparing(File::getName).reversed());
            JSONArray archives = new JSONArray();
            for (int i = 0; i < Math.min(3, sessions.length); i++) {
                requireStopped(context);
                File output = new File(destination, sessions[i].getName() + ".zip");
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
                    copy(sessions[i], sessions[i], zip);
                }
                archives.put(output.getName());
            }
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET, 0);
            JSONObject metadata = new JSONObject();
            metadata.put("export_protocol", 1);
            metadata.put("export_request_id", requestId);
            metadata.put("version_name", info.versionName);
            metadata.put("version_code", info.getLongVersionCode());
            metadata.put("archives", archives);
            metadata.put("audio_and_board_preferences", preferences(context));
            try (FileOutputStream out = new FileOutputStream(new File(destination, "export.json"))) {
                out.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            status.putString("diagnostic_export_protocol", "1");
            status.putString("diagnostic_export_path", destination.getAbsolutePath());
            finish(Activity.RESULT_OK, status);
        } catch (Exception error) {
            // Failure never removes or rewrites an original session or user setting.
            status.putString("diagnostic_export_error", error.getClass().getSimpleName()
                    + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, status);
        }
    }

    @SuppressWarnings("deprecation")
    private static void requireStopped(Context context) throws IOException {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) throw new IOException("Cannot check active assistance");
        for (ActivityManager.RunningServiceInfo running : manager.getRunningServices(1000)) {
            String name = running.service.getClassName();
            if (TARGET.equals(running.service.getPackageName())
                    && (name.equals(TARGET + ".CaptureService")
                    || name.equals(TARGET + ".Match3LiveService"))) {
                throw new IOException("Assistance is still active; stop before exporting");
            }
        }
    }

    private static JSONObject preferences(Context context) throws Exception {
        JSONObject values = new JSONObject();
        File[] files = new File(context.getApplicationInfo().dataDir, "shared_prefs")
                .listFiles(file -> file.getName().endsWith(".xml")
                        && !Files.isSymbolicLink(file.toPath()));
        if (files != null) for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4);
            for (Map.Entry<String, ?> entry : context.getSharedPreferences(name, 0)
                    .getAll().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("match3_") || key.startsWith("cue_tts_")
                        || key.equals("cue_speech_rate_percent") || key.equals("cue_voice_name")
                        || key.equals("cue_bundled_voice") || key.equals("near_two_word")) {
                    values.put(key, entry.getValue());
                }
            }
        }
        return values;
    }

    private static void copy(File root, File source, ZipOutputStream zip) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) return;
        if (source.isDirectory()) {
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Cannot read diagnostic directory");
            for (File child : children) copy(root, child, zip);
            return;
        }
        if (!source.isFile()) return;
        zip.putNextEntry(new ZipEntry(root.toPath().relativize(source.toPath()).toString()));
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }
}
