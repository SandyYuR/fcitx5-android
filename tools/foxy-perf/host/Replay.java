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

/** Runs outside either IME; does not hook their gestures, API, threads or native engine. */
public final class Replay extends Instrumentation {
    Bundle args;
    BenchHost host;
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
            try { if (text == null ? node != null : contains(node, text)) return true; }
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

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                          AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(info);
            JSONObject points = new JSONObject(args.getString("points"));
            int rounds = Integer.parseInt(args.getString("rounds", "8"));
            long launch = now();
            host = (BenchHost) startActivitySync(new Intent(getTargetContext(), BenchHost.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline = now() + 120_000_000_000L;
            while (!imeHas(null) && now() < deadline) SystemClock.sleep(20);
            if (!imeHas(null)) throw new IllegalStateException("IME window did not appear");
            long visible = now();
            JSONArray samples = new JSONArray();
            String expected = "";
            for (int round = 0; round < rounds; round++) {
                long[] n = tap(points.getJSONArray("n"));
                SystemClock.sleep(100);
                long[] i = tap(points.getJSONArray("i"));
                // Observe the final preedit rather than guessing which candidate
                // a fixed coordinate currently represents. No API calls into the IME.
                deadline = now() + 15_000_000_000L;
                long preedit = 0;
                while (now() < deadline) {
                    if (imeHas("ni")) { preedit = now(); break; }
                    SystemClock.sleep(20);
                }
                if (preedit == 0) throw new IllegalStateException("Final ni preedit not observable; refusing an unverified tap");
                // Let that update reach the same frame's candidate views.
                SystemClock.sleep(32);
                if (round == 0 && Boolean.parseBoolean(args.getString("diagnostic", "false"))) screenshot("first-candidates.png");
                long[] select = tap(points.getJSONArray("candidate"));
                expected += "你";
                deadline = now() + 15_000_000_000L;
                String actual;
                while (!(actual = editorText()).equals(expected) && now() < deadline) SystemClock.sleep(10);
                if (!actual.equals(expected)) throw new IllegalStateException("Wrong/duplicate commit: " + actual);
                JSONObject sample = new JSONObject().put("round", round).put("n_down_ns", n[0]).put("n_up_ns", n[1])
                    .put("i_down_ns", i[0]).put("i_up_ns", i[1]).put("preedit_observed_ns", preedit)
                    .put("candidate_down_ns", select[0]).put("candidate_up_ns", select[1]);
                samples.put(sample);
                SystemClock.sleep(180);
            }
            runOnMainSync(() -> host.control("dump"));
            screenshot("editor-after.png");
            JSONObject output = new JSONObject().put("activity_launch_ns", launch).put("ime_window_observed_ns", visible)
                .put("samples", samples).put("output", editorText()).put("method", "external real touch; timestamps before injection");
            try (FileOutputStream out = getTargetContext().openFileOutput("replay.json", 0)) {
                out.write(output.toString(2).getBytes("UTF-8"));
            }
            result.putString("status", "ok");
            finish(0, result);
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
            finish(1, result);
        }
    }
}
