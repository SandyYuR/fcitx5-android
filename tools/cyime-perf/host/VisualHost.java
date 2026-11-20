package io.github.sandyyur.inputbench;

import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;

/** Editor-owned video clock marker and the existing InputConnection timestamp recorder. */
public final class VisualHost extends BenchHost {
    final JSONArray markerTimes = new JSONArray();
    View marker;
    private boolean red;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ((ViewGroup) editor.getParent()).removeView(editor);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        marker = new View(this) {
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(red ? Color.RED : Color.BLUE);
            }
        };
        root.addView(marker, new LinearLayout.LayoutParams(-1, 40));
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        editor.requestFocus();
    }

    void mark() {
        Choreographer.getInstance().postFrameCallback(frameNanos -> {
            markerTimes.put(frameNanos + (now() - System.nanoTime()));
            red = !red;
            marker.invalidate();
        });
    }

    JSONObject markers() throws Exception {
        int[] position = new int[2];
        marker.getLocationOnScreen(position);
        return new JSONObject().put("x", position[0] + marker.getWidth() / 2)
            .put("y", position[1] + marker.getHeight() / 2).put("frame_ns", markerTimes);
    }
}
