package io.github.sandyyur.inputbench;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.FileOutputStream;

/** Physical candidate tap followed by physical client Send; no synthetic IME stall. */
public final class SendReplay extends Replay {
    @Override public void onStart() {
        Bundle result = new Bundle();
        JSONObject data = new JSONObject();
        try {
            AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                          AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            getUiAutomation().setServiceInfo(info);
            imePackage = args.getString("package");
            JSONObject points = new JSONObject(args.getString("points"));
            String sequence = args.getString("sequence", "ni");
            host = (BenchHost) startActivitySync(new Intent(getTargetContext(), SendHost.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            long deadline = now()+240_000_000_000L;
            while (!imeHas(null) && now()<deadline) SystemClock.sleep(20);
            if (!imeHas(null)) throw new IllegalStateException("IME not shown");
            data.put("session_ready_observed_ns",awaitSessionReady());
            for (int index=0; index<sequence.length(); index++) {
                if (index>0) SystemClock.sleep(100);
                tap(points.getJSONArray(sequence.substring(index,index+1)));
            }
            deadline = now()+15_000_000_000L;
            while (!imeHas(sequence) && now()<deadline) SystemClock.sleep(20);
            if (!imeHas(sequence)) throw new IllegalStateException("Final preedit not observed");
            SystemClock.sleep(32);
            long[] candidate = tap(points.getJSONArray("candidate"));
            int gap = Integer.parseInt(args.getString("gap", "20"));
            SystemClock.sleep(gap);
            long[] send = tap(new JSONArray("[160,48]"));
            data.put("candidate_down_ns",candidate[0]).put("candidate_up_ns",candidate[1])
                .put("send_down_ns",send[0]).put("send_up_ns",send[1]).put("scheduled_gap_ms",gap);
            SystemClock.sleep(800);
            runOnMainSync(() -> host.control("dump"));
            screenshot("send-race.png");
            result.putString("status","observed");
        } catch (Throwable error) {
            try {
                data.put("failure",error.toString());
                if (host!=null) { runOnMainSync(() -> host.control("dump")); screenshot("send-race.png"); }
            } catch (Throwable ignored) { }
            result.putString("failure",android.util.Log.getStackTraceString(error));
        }
        try (FileOutputStream out=getTargetContext().openFileOutput("send-race.json",0)) {
            out.write(data.toString(2).getBytes("UTF-8"));
        } catch (Exception error) { result.putString("failure",error.toString()); }
        finish(result.containsKey("failure") ? 1 : 0,result);
    }
}
