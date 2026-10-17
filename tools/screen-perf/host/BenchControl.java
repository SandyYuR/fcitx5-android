package io.github.sandyyur.inputbench;
import android.content.*;
public final class BenchControl extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (BenchHost.current == null) throw new IllegalStateException("Host must be running");
        String command = intent.getStringExtra("command");
        BenchHost.current.control(command);
        if ("clear".equals(command)) {
            // Keep the same Editable/InputConnection, and let the IME receive the
            // external selection update before the test injects another key.
            final PendingResult pending = goAsync();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(pending::finish, 1000);
        }
    }
}
