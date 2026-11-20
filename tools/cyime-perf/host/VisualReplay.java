package io.github.sandyyur.inputbench;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Injects real touches from outside both products, while screenrecord runs separately. */
public final class VisualReplay extends Replay {
    private final JSONArray touches = new JSONArray();
    private VisualHost editorHost;

    private void awaitReady() throws Exception {
        long deadline = now() + 160_000_000_000L;
        while (now() < deadline) {
            String pid = shell("pidof " + imePackage).trim();
            if (pid.matches("[0-9]+") && imeHas()) {
                String logs = shell("logcat -d --pid=" + pid + " -s XimeInputMethodService:W RimeLifecycle:I '*:S'");
                boolean ready = imePackage.equals("com.cyletix.cyime")
                    ? logs.contains("initRimeEngine: ready in ")
                    : java.util.regex.Pattern.compile("RimeLifecycle: bind .*ready=true").matcher(logs).find();
                if (ready) return;
            }
            SystemClock.sleep(120);
        }
        throw new IllegalStateException("Target IME was not ready: " + imePackage);
    }

    private void touch(JSONArray rect, long due, String key, int round) throws Exception {
        SystemClock.sleep(Math.max(0, due - SystemClock.uptimeMillis()));
        float x = (rect.getInt(0) + rect.getInt(2)) / 2f;
        float y = (rect.getInt(1) + rect.getInt(3)) / 2f;
        JSONObject event = new JSONObject().put("key", key).put("round", round)
            .put("due_ns", due * 1_000_000L + (now() - SystemClock.uptimeMillis() * 1_000_000L));
        touches.put(event);
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            String name = action == MotionEvent.ACTION_DOWN ? "down" : "up";
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(Math.max(0, down + 32 - SystemClock.uptimeMillis()));
            MotionEvent motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            motion.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                event.put(name + "_ns", now());
                boolean injected = getUiAutomation().injectInputEvent(motion, true);
                event.put(name + "_return_ns", now());
                if (!injected) throw new IllegalStateException("Touch injection failed: " + key);
            } finally {
                motion.recycle();
            }
        }
    }

    private void markVideo() {
        for (int i = 0; i < 6; i++) {
            runOnMainSync(editorHost::mark);
            SystemClock.sleep(240);
        }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        JSONObject evidence = new JSONObject();
        int status = 1;
        try {
            imePackage = args.getString("package");
            if (!"com.cyletix.cyime".equals(imePackage) && !"org.fcitx.fcitx5.android.fx.rime".equals(imePackage)) {
                throw new IllegalArgumentException("Unknown target IME");
            }
            AccessibilityServiceInfo accessibility = getUiAutomation().getServiceInfo();
            accessibility.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(accessibility);
            editorHost = (VisualHost) startActivitySync(new Intent(getTargetContext(), VisualHost.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            host = editorHost;
            awaitReady();
            screenshot("before.png");
            markVideo();
            JSONObject bounds = new JSONObject(args.getString("bounds"));
            JSONArray words = new JSONArray(args.getString("words"));
            int cadence = Integer.parseInt(args.getString("cadence"));
            StringBuilder expected = new StringBuilder();
            for (int round = 0; round < words.length(); round++) {
                JSONArray word = words.getJSONArray(round);
                String code = word.getString(0);
                long start = SystemClock.uptimeMillis() + 40;
                for (int i = 0; i < code.length(); i++) {
                    String key = code.substring(i, i + 1);
                    touch(bounds.getJSONArray(key), start + i * cadence, key, round);
                }
                SystemClock.sleep(460);
                if (round == 0) screenshot("first-candidates.png");
                touch(bounds.getJSONArray("select"), SystemClock.uptimeMillis(), "select", round);
                expected.append(word.getString(1));
                long deadline = now() + 2_000_000_000L;
                while (!expected.toString().equals(editorText()) && now() < deadline) SystemClock.sleep(20);
                if (!expected.toString().equals(editorText())) {
                    screenshot("wrong-output.png");
                    throw new IllegalStateException("Expected " + expected + ", editor contained " + editorText());
                }
                SystemClock.sleep(220);
            }
            markVideo();
            evidence.put("output", editorText()).put("markers", editorHost.markers());
            status = 0;
        } catch (Throwable failure) {
            result.putString("failure", android.util.Log.getStackTraceString(failure));
            try { evidence.put("failure", failure.toString()); } catch (Exception ignored) { }
        } finally {
            try {
                if (editorHost != null) runOnMainSync(() -> editorHost.control("dump"));
                try (FileOutputStream file = getTargetContext().openFileOutput("touches.json", 0)) {
                    evidence.put("touches", touches);
                    file.write(evidence.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable failure) {
                result.putString("evidence_failure", failure.toString());
                status = 1;
            }
            result.putString("status", status == 0 ? "ok" : "failed");
            finish(status, result);
        }
    }
}
