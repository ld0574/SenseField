package com.openkhub.sensefield;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Same-signature ADB helper: reads finished records without opening export UI or changing settings. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticExportInstrumentedTest {
    @Test public void copyPrivateDiagnosticsForAuthorizedAdbSession() throws Exception {
        Assume.assumeTrue("Only run for a requested ADB diagnostic collection", "true".equals(
                InstrumentationRegistry.getArguments().getString("authorizedDiagnosticsExport")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File destination = context.getExternalFilesDir("adb-diagnostics");
        assertNotNull(destination);
        if (!destination.isDirectory() && !destination.mkdirs()) throw new java.io.IOException("Export directory");
        File source = new File(context.getFilesDir(), "diagnostics");
        File[] sessions = source.listFiles(file -> file.isDirectory() && file.getName().startsWith("diag-"));
        if (sessions == null) sessions = new File[0];
        Arrays.sort(sessions, Comparator.comparing(File::getName).reversed());
        JSONObject metadata = new JSONObject();
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        metadata.put("version_name", info.versionName);
        metadata.put("version_code", info.getLongVersionCode());
        org.json.JSONArray exported = new org.json.JSONArray();
        for (int i = 0; i < Math.min(3, sessions.length); i++) {
            File output = new File(destination, sessions[i].getName() + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
                copy(sessions[i], sessions[i], zip);
            }
            exported.put(output.getName());
        }
        metadata.put("archives", exported);
        JSONObject preferences = new JSONObject();
        File[] files = new File(context.getApplicationInfo().dataDir, "shared_prefs")
                .listFiles(file -> file.getName().endsWith(".xml"));
        if (files != null) for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4);
            for (Map.Entry<String, ?> value : context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                String key = value.getKey();
                if (key.startsWith("match3_") || key.startsWith("cue_tts_") || key.equals("cue_bundled_voice"))
                    preferences.put(key, value.getValue());
            }
        }
        metadata.put("audio_and_board_preferences", preferences);
        try (FileOutputStream output = new FileOutputStream(new File(destination, "export.json"))) {
            output.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        Bundle status = new Bundle();
        status.putString("diagnostic_export_path", destination.getAbsolutePath());
        status.putString("diagnostic_export_archives", exported.toString());
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }

    private static void copy(File root, File source, ZipOutputStream zip) throws Exception {
        if (source.isDirectory()) {
            File[] children = source.listFiles();
            if (children != null) for (File child : children) copy(root, child, zip);
            return;
        }
        String name = root.toPath().relativize(source.toPath()).toString();
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream input = new FileInputStream(source)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
        }
        zip.closeEntry();
    }
}
