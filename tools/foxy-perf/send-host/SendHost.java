package io.github.sandyyur.inputbench;

import android.os.Bundle;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;

/** A separate fake chat client; never installed into or hooked into either IME. */
public final class SendHost extends BenchHost {
    final JSONArray sends = new JSONArray();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ((ViewGroup) editor.getParent()).removeView(editor);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        Button send = new Button(this);
        send.setText("Send (test client)");
        send.setOnClickListener(view -> {
            sends.put(record(editor.getText().toString()));
            editor.setText("");
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).restartInput(editor);
        });
        layout.addView(send, new LinearLayout.LayoutParams(-1, Math.round(48*getResources().getDisplayMetrics().density)));
        layout.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        editor.setText("前文");
        editor.setSelection(editor.length());
        editor.requestFocus();
    }
    @Override void control(String command) {
        super.control(command);
        if (!"dump".equals(command)) return;
        try {
            File path = new File(getFilesDir(), "candidate-editor.json");
            JSONObject data = new JSONObject(new String(Files.readAllBytes(path.toPath()), "UTF-8"));
            data.put("sends", sends);
            Files.write(path.toPath(), data.toString(2).getBytes("UTF-8"));
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
