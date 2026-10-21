package io.github.sandyyur.inputbench;
import android.content.*;
public final class BenchControl extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (BenchHost.current == null) throw new IllegalStateException("Host must be running");
        BenchHost.current.control(intent.getStringExtra("command"));
    }
}
