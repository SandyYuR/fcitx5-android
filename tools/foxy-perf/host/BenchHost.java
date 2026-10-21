package io.github.sandyyur.inputbench;

import android.app.Activity;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.EditText;
import org.json.*;
import java.io.*;

/** Neutral editor. Timestamps are recorded in callbacks, before any host-side polling. */
public class BenchHost extends Activity {
    static BenchHost current;
    EditText editor;
    final JSONArray connections = new JSONArray();
    final JSONArray commits = new JSONArray();
    final JSONArray edits = new JSONArray();
    final JSONArray draws = new JSONArray();
    boolean pendingDraw;
    long now() { return SystemClock.elapsedRealtimeNanos(); }
    JSONObject record(String text) {
        try { return new JSONObject().put("at_ns", now()).put("text", text); }
        catch (JSONException e) { throw new RuntimeException(e); }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        editor = new EditText(this) {
            @Override public InputConnection onCreateInputConnection(EditorInfo info) {
                InputConnection original = super.onCreateInputConnection(info);
                connections.put(now());
                return original == null ? null : new InputConnectionWrapper(original, false) {
                    @Override public boolean commitText(CharSequence text, int cursor) {
                        commits.put(record(text.toString()));
                        return super.commitText(text, cursor);
                    }
                };
            }
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (pendingDraw) { pendingDraw = false; draws.put(record(getText().toString())); }
            }
        };
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        editor.setCursorVisible(false);
        editor.setGravity(Gravity.TOP | Gravity.LEFT);
        editor.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) { edits.put(record(s.toString())); pendingDraw = true; }
        });
        setContentView(editor);
        editor.requestFocus();
        editor.post(() -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT));
    }
    void control(String command) {
        if ("dump".equals(command)) {
            try {
                JSONObject result = new JSONObject().put("text", editor.getText().toString()).put("connections", connections)
                    .put("commits", commits).put("edits", edits).put("draws", draws);
                try (FileOutputStream out = openFileOutput("candidate-editor.json", MODE_PRIVATE)) { out.write(result.toString(2).getBytes("UTF-8")); }
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
}
