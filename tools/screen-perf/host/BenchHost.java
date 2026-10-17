package io.github.sandyyur.inputbench;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import org.json.*;
import java.io.*;

/** A neutral editor process: contains no IME libraries or benchmark collectors. */
public final class BenchHost extends Activity {
    static BenchHost current;
    EditText editor;
    View marker;
    final JSONArray frames = new JSONArray();
    int sequence;
    boolean pending;
    long markerFrameNanos;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        marker = new View(this) {
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    Choreographer.getInstance().postFrameCallback(nanos -> {
                        sequence++; markerFrameNanos = nanos; pending = true; invalidate();
                    });
                }
                return true;
            }
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(sequence % 2 == 0 ? Color.BLUE : Color.RED);
                if (pending) {
                    pending = false;
                    try { frames.put(new JSONObject().put("sequence", sequence).put("frame_ns", markerFrameNanos)); }
                    catch (JSONException e) { throw new RuntimeException(e); }
                }
            }
        };
        marker.setFocusable(false);
        root.addView(marker, new LinearLayout.LayoutParams(-1, Math.round(48 * getResources().getDisplayMetrics().density)));
        editor = new EditText(this);
        editor.setTextSize(16);
        editor.setGravity(Gravity.TOP | Gravity.LEFT);
        editor.setBackgroundColor(Color.WHITE);
        editor.setTextColor(Color.BLACK);
        editor.setPadding(16, 12, 16, 12);
        editor.setCursorVisible(false);
        editor.setSingleLine(false);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        editor.requestFocus();
        editor.postDelayed(() -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT), 400);
    }
    JSONArray bounds(View v) {
        int[] xy = new int[2]; v.getLocationOnScreen(xy);
        return new JSONArray().put(xy[0]).put(xy[1]).put(xy[0]+v.getWidth()).put(xy[1]+v.getHeight());
    }
    void control(String command) {
        if ("clear".equals(command)) { editor.setText(""); editor.requestFocus(); }
        if ("dump".equals(command)) {
            try {
                JSONObject obj = new JSONObject().put("text", editor.getText().toString())
                    .put("editor", bounds(editor)).put("marker", bounds(marker)).put("marker_frames", frames);
                try (FileOutputStream out = openFileOutput("state.json", MODE_PRIVATE)) { out.write(obj.toString(2).getBytes("UTF-8")); }
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
