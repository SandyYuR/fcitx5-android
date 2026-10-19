package io.github.sandyyur.inputbench;

import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;

/** Runs outside either IME; does not hook their gestures, API, threads or native engine. */
public class Replay extends Instrumentation {
    Bundle args;
    BenchHost host;
    String imePackage;
    final JSONArray samples = new JSONArray();
    final JSONArray phases = new JSONArray();
    long now() { return SystemClock.elapsedRealtimeNanos(); }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }

    long[] tap(JSONArray point) throws Exception {
        long down = SystemClock.uptimeMillis();
        long[] times = new long[2];
        for (int action = 0; action < 2; action++) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                (float) point.getDouble(0), (float) point.getDouble(1), 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                times[action] = now();
                if (!getUiAutomation().injectInputEvent(event, true)) throw new IllegalStateException("Touch injection failed");
            } finally { event.recycle(); }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(24);
        }
        return times;
    }

    boolean imeHas() {
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo node = window.getRoot();
            try {
                if (node != null && imePackage.equals(String.valueOf(node.getPackageName()))) return true;
            }
            finally { if (node != null) node.recycle(); window.recycle(); }
        }
        return false;
    }

    String editorText() {
        final String[] value = {null};
        runOnMainSync(() -> value[0] = host.editor.getText().toString());
        return value[0];
    }
    String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(fd.getFileDescriptor());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
    long awaitSessionReady() throws Exception {
        if (!imePackage.equals("com.fxliang.foxy") && !imePackage.equals("io.github.sandyyur.rime.direct"))
            throw new IllegalArgumentException("Unexpected IME");
        long deadline = now()+240_000_000_000L;
        String stableMarker = "";
        long stableSince = 0;
        while (now()<deadline) {
            String pid = shell("pidof "+imePackage).trim();
            if (pid.matches("[0-9]+")) {
                String logs = shell("logcat -d -v threadtime --pid="+pid+" -s foxy:I FoxyImeLifecycle:I RimeLifecycle:I '*:S'");
                String marker = "";
                if (imePackage.equals("com.fxliang.foxy")) {
                    int start = logs.lastIndexOf("FoxyImeLifecycle: onStartInput ");
                    int option = logs.lastIndexOf("type=option value=soft_cursor");
                    if (start >= 0 && option > start) {
                        String binding = logs.substring(start, logs.indexOf('\n', start));
                        if (binding.contains("package=io.github.sandyyur.inputbench "))
                            marker = binding+logs.substring(option-60, option);
                    }
                } else {
                    java.util.regex.Matcher bindings = java.util.regex.Pattern.compile(
                        "RimeLifecycle: input_binding generation=(\\d+)[^\\n]*").matcher(logs);
                    String generation = "", binding = "";
                    while (bindings.find()) { generation = bindings.group(1); binding = bindings.group(); }
                    java.util.regex.Matcher ready = java.util.regex.Pattern.compile(
                        "RimeLifecycle: bind generation=(\\d+)[^\\n]*ready=true[^\\n]*").matcher(logs);
                    String resolved = "", resolvedLine = "";
                    while (ready.find()) { resolved = ready.group(1); resolvedLine = ready.group(); }
                    if (!generation.isEmpty() && generation.equals(resolved) && binding.contains("needs_rime=true"))
                        marker = binding+resolvedLine;
                }
                if (!marker.isEmpty() && imeHas()) {
                    if (!marker.equals(stableMarker)) { stableMarker = marker; stableSince = now(); }
                    if (now()-stableSince >= 500_000_000L) return now();
                } else { stableMarker = ""; stableSince = 0; }
            }
            SystemClock.sleep(100);
        }
        throw new IllegalStateException("Actual Rime session not ready; keyboard visibility is insufficient");
    }
    void screenshot(String name) throws Exception {
        Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) throw new IllegalStateException("Screenshot unavailable");
        try (FileOutputStream out = getTargetContext().openFileOutput(name, 0)) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
    }

    void phase(String phase, JSONObject points, int rounds) throws Exception {
        long launch = now();
        host = (BenchHost) startActivitySync(new Intent(getTargetContext(), BenchHost.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        long deadline = now() + 240_000_000_000L;
        while (!imeHas() && now() < deadline) SystemClock.sleep(20);
        if (!imeHas()) throw new IllegalStateException("Requested IME window did not appear");
        long shown = now();
        long ready = awaitSessionReady();
        phases.put(new JSONObject().put("phase", phase).put("launch_ns", launch).put("ime_observed_ns", shown)
            .put("session_ready_observed_ns", ready));
        String expected = "";
        String sequence = args.getString("sequence", "ni");
        String word = args.getString("word", "你");
        for (int round = 0; round < rounds; round++) {
            JSONArray keys = new JSONArray();
            long[] last = null;
            for (int index = 0; index < sequence.length(); index++) {
                if (index > 0) SystemClock.sleep(100);
                String key = sequence.substring(index, index+1);
                last = tap(points.getJSONArray(key));
                keys.put(new JSONObject().put("key", key).put("down_ns", last[0]).put("up_ns", last[1]));
            }
            JSONObject sample = new JSONObject().put("phase", phase).put("round", round).put("keys", keys)
                .put("last_key_down_ns", last[0]).put("last_key_up_ns", last[1]);
            samples.put(sample);
            // Foxy's floating preedit is not exposed by this accessibility tree.
            // Wanxiang fuzzy spelling can also format a valid input differently.
            // Isolate selection after the same fixed settling time in both UIs;
            // validate actual editor delivery, never infer key loss from preedit.
            SystemClock.sleep(1000);
            sample.put("settle_finished_ns", now());
            if (round == 0) screenshot(phase+"-candidates.png");
            long[] select = tap(points.getJSONArray("candidate"));
            sample.put("candidate_down_ns", select[0]).put("candidate_up_ns", select[1]);
            expected += word;
            deadline = now() + 15_000_000_000L;
            String actual;
            while (!(actual = editorText()).equals(expected) && now() < deadline) SystemClock.sleep(10);
            if (!actual.equals(expected)) throw new IllegalStateException("Wrong/duplicate commit: " + actual);
            SystemClock.sleep(180);
        }
        runOnMainSync(() -> host.control("dump"));
        File data = getTargetContext().getFilesDir();
        Files.copy(new File(data, "candidate-editor.json").toPath(), new File(data, phase+"-editor.json").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        screenshot(phase+"-editor.png");
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                          AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(info);
            JSONObject points = new JSONObject(args.getString("points"));
            imePackage = args.getString("package");
            int rounds = Integer.parseInt(args.getString("rounds", "8"));
            phase("fresh", points, rounds);
            runOnMainSync(() -> host.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)));
            long deadline = now()+5_000_000_000L;
            while (imeHas() && now()<deadline) SystemClock.sleep(20);
            if (imeHas()) throw new IllegalStateException("Non-editor Settings did not hide the IME");
            phases.put(new JSONObject().put("phase", "noneditor").put("hidden_observed_ns", now()));
            SystemClock.sleep(500);
            phase("rebind", points, rounds);
            JSONObject output = new JSONObject().put("phases", phases).put("samples", samples)
                .put("output", editorText()).put("word", args.getString("word", "你"))
                .put("method", "external real touch; timestamps before injection");
            try (FileOutputStream out = getTargetContext().openFileOutput("replay.json", 0)) {
                out.write(output.toString(2).getBytes("UTF-8"));
            }
            result.putString("status", "ok");
            finish(0, result);
        } catch (Throwable error) {
            try {
                if (host != null) { runOnMainSync(() -> host.control("dump")); screenshot("failure.png"); }
                JSONObject output = new JSONObject().put("failure", error.toString()).put("phases", phases).put("samples", samples);
                try (FileOutputStream out = getTargetContext().openFileOutput("replay.json", 0)) {
                    out.write(output.toString(2).getBytes("UTF-8"));
                }
            } catch (Throwable ignored) { }
            result.putString("failure", android.util.Log.getStackTraceString(error));
            finish(1, result);
        }
    }
}
