package com.feurstagram.extension;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/**
 * One-shot relay that makes "clear cache and restart" actually restart.
 *
 * The dying process cannot bring itself back: an activity it starts just before
 * killing itself is dropped by the system ("app died, no saved state"), and one
 * queued for after the kill (an alarm PendingIntent) is refused as a background
 * activity launch. So the relay runs in a process of its own — declared by the
 * Restart relay patch — kills the main process from the outside, and only then
 * starts Instagram from its own live foreground window.
 */
public final class RestartActivity extends Activity {

    /** Pid of the process to kill, put in by {@link CacheCleaner}. */
    public static final String EXTRA_PID = "com.feurstagram.extension.RESTART_PID";

    /** Intent that launches this relay in its own task. */
    static Intent intentFor(Context context, int pid) {
        Intent intent = new Intent(context, RestartActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(EXTRA_PID, pid);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            int pid = getIntent().getIntExtra(EXTRA_PID, -1);
            // Same uid, so the kill is allowed. Everything Instagram held in
            // memory goes with it; the caches on disk are already wiped.
            if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid);

            Intent target = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (target != null) {
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(target);
            }
        } catch (Throwable ignored) {
        }
        // Leave no trace: this process holds nothing, so the system reaps it.
        finish();
    }
}
