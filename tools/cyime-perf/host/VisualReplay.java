package io.github.sandyyur.inputbench;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/** Injects real touches from outside both products, while screenrecord runs separately. */
public final class VisualReplay extends Replay {
    private final JSONArray touches = new JSONArray();
    private VisualHost editorHost;
    private Object inputManager;
    private Method injectTouch;

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

    private void waitUntil(long dueNs) {
        long remaining;
        while ((remaining = dueNs - now()) > 0) {
            if (remaining > 2_000_000L) LockSupport.parkNanos(remaining - 2_000_000L);
        }
    }

    private void touch(JSONArray rect, long dueNs, String key, int round) throws Exception {
        float x = (rect.getInt(0) + rect.getInt(2)) / 2f;
        float y = (rect.getInt(1) + rect.getInt(3)) / 2f;
        JSONObject event = new JSONObject().put("key", key).put("round", round).put("due_ns", dueNs);
        touches.put(event);
        waitUntil(dueNs);
        long down = SystemClock.uptimeMillis();
        long downAt = 0L;
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            String name = action == MotionEvent.ACTION_DOWN ? "down" : "up";
            if (action == MotionEvent.ACTION_UP) waitUntil(downAt + 32_000_000L);
            MotionEvent motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
            motion.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                long injectedAt = now();
                boolean injected = injectTouch == null
                    ? getUiAutomation().injectInputEvent(motion, true)
                    : (Boolean) injectTouch.invoke(inputManager, motion, 0);
                long returnedAt = now();
                if (action == MotionEvent.ACTION_DOWN) downAt = injectedAt;
                event.put(name + "_ns", injectedAt).put(name + "_return_ns", returnedAt)
                    .put(name + "_event_uptime_ms", motion.getEventTime());
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

    private static final class Label {
        final String text;
        final Rect bounds;

        Label(CharSequence text, Rect bounds) {
            this.text = text.toString();
            this.bounds = new Rect(bounds);
        }
    }

    private void collectLabels(AccessibilityNodeInfo node, List<Label> labels) {
        if (node == null) return;
        try {
            if (!node.isVisibleToUser()) return;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                if (node.getText() != null) labels.add(new Label(node.getText(), bounds));
                if (node.getContentDescription() != null) labels.add(new Label(node.getContentDescription(), bounds));
            }
            for (int i = 0; i < node.getChildCount(); i++) collectLabels(node.getChild(i), labels);
        } finally {
            node.recycle();
        }
    }

    private List<Label> keyboardLabels() {
        List<Label> labels = new ArrayList<>();
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            try {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) collectLabels(window.getRoot(), labels);
            } finally {
                window.recycle();
            }
        }
        return labels;
    }

    private static JSONArray rect(Rect rect) {
        return new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom);
    }

    private void expandScreenKeyboard() throws Exception {
        if (!imePackage.equals("com.cyletix.cyime")) return;
        for (Label label : keyboardLabels()) {
            if (!label.text.equals("展开屏幕键盘")) continue;
            touch(rect(label.bounds), now(), "setup", -1);
            touches.remove(touches.length() - 1);
            SystemClock.sleep(550);
            return;
        }
    }

    private Rect keyBounds(List<Label> labels, String key) {
        Rect largest = null;
        for (Label label : labels) {
            Rect bounds = label.bounds;
            if (bounds.top > 480 && label.text.trim().equalsIgnoreCase(key)
                    && (largest == null || bounds.width() * bounds.height() > largest.width() * largest.height())) {
                largest = bounds;
            }
        }
        return largest;
    }

    private Rect directToolbar(List<Label> labels) {
        for (Label label : labels) {
            if (label.text.equals("Status area") && label.bounds.left == 0 && label.bounds.width() == 40
                    && label.bounds.height() == 40 && label.bounds.top > 580) return label.bounds;
        }
        throw new IllegalStateException("Cannot anchor 中州鹅 split QWERTY to the recorded toolbar geometry");
    }

    private Rect splitKey(String key, Rect toolbar) {
        // Pixel centers from the 480x960 calibration screenshot. Anchor Y to
        // the 40px toolbar, then require actual Chinese commits during replay.
        String[] letters = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        int[][] centers = {{19, 58, 96, 135, 174, 307, 346, 385, 423, 462},
                           {19, 58, 96, 135, 174, 307, 346, 385, 423},
                           {69, 107, 146, 174, 337, 375, 414}};
        for (int row = 0; row < letters.length; row++) {
            int index = letters[row].indexOf(key);
            if (index < 0) continue;
            int x = centers[row][index];
            int y = toolbar.bottom + 31 + 65 * row;
            return new Rect(x - 15, y - 26, x + 15, y + 26);
        }
        throw new IllegalArgumentException("Unknown letter " + key);
    }

    private void recordLabels(JSONObject evidence, String name, List<Label> labels) throws Exception {
        JSONArray items = new JSONArray();
        for (Label label : labels) items.put(new JSONObject().put("text", label.text).put("bounds", rect(label.bounds)));
        evidence.put(name, items);
    }

    private void calibrate(JSONObject evidence) throws Exception {
        JSONObject bounds = new JSONObject();
        List<Label> idle = keyboardLabels();
        recordLabels(evidence, "idle_labels", idle);
        String keys = args.getString("letters");
        Rect fallbackToolbar = null;
        if (imePackage.equals("org.fcitx.fcitx5.android.fx.rime") && keyBounds(idle, "a") == null) {
            fallbackToolbar = directToolbar(idle);
            evidence.put("geometry", "verified split QWERTY screenshot anchored to idle toolbar");
        }
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.substring(i, i + 1);
            Rect keyRect = fallbackToolbar == null ? keyBounds(idle, key) : splitKey(key, fallbackToolbar);
            if (keyRect == null) throw new IllegalStateException("No accessible key bounds for " + key);
            bounds.put(key, rect(keyRect));
        }
        for (String key : new String[]{"n", "i"}) {
            touch(bounds.getJSONArray(key), now(), key, 0);
            SystemClock.sleep(200);
        }
        SystemClock.sleep(600);
        screenshot("first-candidates.png");
        List<Label> composing = keyboardLabels();
        recordLabels(evidence, "composing_labels", composing);
        int top = fallbackToolbar == null ? 960 : fallbackToolbar.bottom;
        for (int i = 0; fallbackToolbar == null && i < keys.length(); i++) {
            String key = keys.substring(i, i + 1);
            Rect idleRect = keyBounds(idle, key);
            Rect active = keyBounds(composing, key);
            if (active == null) throw new IllegalStateException("No accessible bounds for composing key " + key);
            top = Math.min(top, active.top);
            if (!active.contains(idleRect.centerX(), idleRect.centerY())) {
                throw new IllegalStateException(key + " moved beyond the idle touch point while composing");
            }
        }
        Rect selected = null;
        for (Label label : composing) {
            Rect candidate = label.bounds;
            if (candidate.top >= top - 180 && candidate.bottom <= top && label.text.matches("\\s*[1１]?[.、 ]*你(?:\\s.*)?")) {
                if (selected == null || candidate.left < selected.left) selected = candidate;
            }
        }
        if (selected == null && fallbackToolbar != null) {
            selected = new Rect(4, fallbackToolbar.top, 44, fallbackToolbar.bottom);
        }
        if (selected == null) throw new IllegalStateException("Candidate 你 is absent from the IME accessibility window");
        bounds.put("select", rect(selected));
        bounds.put("candidate", rect(new Rect(0, selected.top, 480, selected.bottom)));
        evidence.put("bounds", bounds);
        touch(bounds.getJSONArray("select"), now(), "select", 0);
        SystemClock.sleep(600);
        screenshot("committed.png");
        evidence.put("output", editorText());
        if (!"你".equals(editorText())) throw new IllegalStateException("Chinese candidate touch did not commit 你");
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
            expandScreenKeyboard();
            screenshot("before.png");
            if ("true".equals(args.getString("probe"))) {
                calibrate(evidence);
            } else {
                replay(evidence);
            }
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

    private void replay(JSONObject evidence) throws Exception {
        // Match the repository cadence probe: avoid synchronizing Android's
        // surface transactions and animations around every touch.
        Class<?> inputClass = Class.forName("android.hardware.input.InputManagerGlobal");
        inputManager = inputClass.getMethod("getInstance").invoke(null);
        injectTouch = inputClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        getUiAutomation().adoptShellPermissionIdentity("android.permission.INJECT_EVENTS");
        try {
            String overlap = args.getString("overlap");
            if (overlap == null) replayWithCadence(evidence);
            else replayOverlap(evidence, overlap);
        } finally {
            getUiAutomation().dropShellPermissionIdentity();
        }
    }

    private void replayOverlap(JSONObject evidence, String mode) throws Exception {
        JSONObject bounds = new JSONObject(args.getString("bounds"));
        String code = mode.equals("one-g") ? "zhonguo" : "zhongguo";
        long start = now() + 40_000_000L;
        for (int i = 0; i < code.length(); i++) {
            String key = code.substring(i, i + 1);
            long due = start + i * 120_000_000L;
            if (i == 4 && (mode.equals("older-up-first") || mode.equals("newer-up-first") || mode.equals("cancel-second"))) {
                doubleTouch(bounds.getJSONArray(key), due, mode);
                i++;
            } else {
                touch(bounds.getJSONArray(key), due, key, 0);
            }
        }
        SystemClock.sleep(850);
        screenshot("first-candidates.png");
        recordLabels(evidence, "overlap_candidates", keyboardLabels());
        touch(bounds.getJSONArray("select"), now(), "select", 0);
        SystemClock.sleep(500);
        evidence.put("output", editorText());
        screenshot("committed.png");
    }

    private void doubleTouch(JSONArray bounds, long start, String mode) throws Exception {
        boolean olderUpFirst = mode.equals("older-up-first");
        boolean cancel = mode.equals("cancel-second");
        float x = (bounds.getInt(0) + bounds.getInt(2)) / 2f;
        float y = (bounds.getInt(1) + bounds.getInt(3)) / 2f;
        MotionEvent.PointerProperties[] pointers = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            pointers[i] = new MotionEvent.PointerProperties();
            pointers[i].id = i;
            pointers[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = x + (i == 0 ? -3 : 3);
            coords[i].y = y;
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        int[] offsets = {0, 28, 65, 94};
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_POINTER_UP | ((olderUpFirst ? 0 : 1) << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            MotionEvent.ACTION_UP};
        long downTime = 0;
        for (int i = 0; i < (cancel ? 3 : actions.length); i++) {
            waitUntil(start + offsets[i] * 1_000_000L);
            long eventTime = SystemClock.uptimeMillis();
            if (i == 0) downTime = eventTime;
            int count = i == 0 || i == 3 ? 1 : 2;
            int first = i == 3 && olderUpFirst ? 1 : 0;
            MotionEvent motion = MotionEvent.obtain(downTime, eventTime, actions[i], count,
                count == 2 ? pointers : new MotionEvent.PointerProperties[]{pointers[first]},
                count == 2 ? coords : new MotionEvent.PointerCoords[]{coords[first]},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try {
                long at = now();
                if (!(Boolean) injectTouch.invoke(inputManager, motion, 0)) throw new IllegalStateException("Two-pointer injection failed");
                touches.put(new JSONObject().put("key", "g").put("action", actions[i]).put("at_ns", at));
            } finally {
                motion.recycle();
            }
        }
    }

    private void replayWithCadence(JSONObject evidence) throws Exception {
        markVideo();
        JSONObject bounds = new JSONObject(args.getString("bounds"));
        JSONArray words = new JSONArray(args.getString("words"));
        int cadence = Integer.parseInt(args.getString("cadence"));
        boolean sentence = "true".equals(args.getString("sentence"));
        int pauseAfter = Integer.parseInt(args.getString("pauseAfter", "0"));
        int pauseMs = Integer.parseInt(args.getString("pauseMs", "0"));
        StringBuilder expected = new StringBuilder();
        for (int round = 0; round < words.length(); round++) {
            JSONArray word = words.getJSONArray(round);
            String code = word.getString(0);
            long start = now() + 40_000_000L;
            for (int i = 0; i < code.length(); i++) {
                String key = code.substring(i, i + 1);
                long scheduled = start + (i * cadence + (i >= pauseAfter ? pauseMs : 0)) * 1_000_000L;
                touch(bounds.getJSONArray(key), scheduled, key, round);
            }
            SystemClock.sleep(sentence ? 950 : 460);
            if (round == 0) screenshot("first-candidates.png");
            if (sentence) {
                selectSentence(evidence, bounds, word.getString(1), round);
                continue;
            }
            touch(bounds.getJSONArray("select"), now(), "select", round);
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
    }

    private void selectSentence(JSONObject evidence, JSONObject bounds, String expected, int round) throws Exception {
        JSONArray candidate = bounds.getJSONArray("candidate");
        JSONArray target = bounds.getJSONArray("select");
        List<Label> composing = keyboardLabels();
        List<Label> candidateLabels = new ArrayList<>();
        for (Label label : composing) {
            Rect area = label.bounds;
            if (area.top >= candidate.getInt(1) - 24 && area.bottom <= candidate.getInt(3) + 24) {
                candidateLabels.add(label);
                if (label.text.replaceFirst("^\\s*\\d+[.、 ]*", "").trim().equals(expected)) {
                    target = rect(area);
                }
            }
        }
        recordLabels(evidence, "sentence_candidates", candidateLabels);
        evidence.put("sentence_selection", target);
        touch(target, now(), "select", round);
        SystemClock.sleep(650);
        if (!expected.equals(editorText())) screenshot("wrong-output.png");
    }
}
