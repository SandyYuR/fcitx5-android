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
import java.nio.file.Files;

/** Runs outside either IME; does not hook their gestures, API, threads or native engine. */
public final class Replay extends Instrumentation {
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

    boolean contains(AccessibilityNodeInfo node, String expected) {
        if (node == null) return false;
        CharSequence text = node.getText();
        if (text != null && text.toString().replace(" ", "").replace("'", "").equalsIgnoreCase(expected)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            try { if (contains(child, expected)) return true; }
            finally { if (child != null) child.recycle(); }
        }
        return false;
    }

    boolean imeHas(String text) {
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo node = window.getRoot();
            try {
                if (node != null && imePackage.equals(String.valueOf(node.getPackageName())) &&
                    (text == null || contains(node, text))) return true;
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
        long deadline = now() + 120_000_000_000L;
        while (!imeHas(null) && now() < deadline) SystemClock.sleep(20);
        if (!imeHas(null)) throw new IllegalStateException("Requested IME window did not appear");
        phases.put(new JSONObject().put("phase", phase).put("launch_ns", launch).put("ime_observed_ns", now()));
        String expected = "";
        for (int round = 0; round < rounds; round++) {
            long[] n = tap(points.getJSONArray("n"));
            SystemClock.sleep(100);
            long[] i = tap(points.getJSONArray("i"));
            JSONObject sample = new JSONObject().put("phase", phase).put("round", round)
                .put("n_down_ns", n[0]).put("n_up_ns", n[1]).put("i_down_ns", i[0]).put("i_up_ns", i[1]);
            samples.put(sample);
            deadline = now() + 15_000_000_000L;
            long preedit = 0;
            while (now() < deadline) {
                if (imeHas("ni")) { preedit = now(); break; }
                SystemClock.sleep(20);
            }
            if (preedit == 0) throw new IllegalStateException("Final ni preedit not observable; refusing an unverified tap");
            SystemClock.sleep(32);
            if (round == 0 && Boolean.parseBoolean(args.getString("diagnostic", "false"))) screenshot(phase+"-candidates.png");
            long[] select = tap(points.getJSONArray("candidate"));
            sample.put("preedit_observed_ns", preedit).put("candidate_down_ns", select[0]).put("candidate_up_ns", select[1]);
            expected += "你";
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
            while (imeHas(null) && now()<deadline) SystemClock.sleep(20);
            if (imeHas(null)) throw new IllegalStateException("Non-editor Settings did not hide the IME");
            phases.put(new JSONObject().put("phase", "noneditor").put("hidden_observed_ns", now()));
            SystemClock.sleep(500);
            phase("rebind", points, rounds);
            JSONObject output = new JSONObject().put("phases", phases).put("samples", samples)
                .put("output", editorText()).put("method", "external real touch; timestamps before injection");
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
