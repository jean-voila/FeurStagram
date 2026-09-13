package com.feurstagram.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A card inviting the user to follow the official Feurstagram Instagram account,
 * shown once on the first launch after every install or update — before the
 * "What's new" card, which only runs once this one is out of the way.
 *
 * "After an install or update" is read from the package's {@code lastUpdateTime}
 * rather than its versionName: a Feurstagram re-release on the same Instagram base
 * (a {@code -1} tag) keeps the versionName but is still an update, and a fresh
 * install has no stored time at all.
 *
 * The follow button opens the profile inside this very app (the signature-check
 * bypass makes Instagram accept its own deep links), falling back to any handler
 * — a browser — if that fails.
 */
public final class FollowPrompt {

    private static final String PROFILE_URL = "https://www.instagram.com/feurstagram_official/";

    /**
     * Let the activity finish settling before a window goes on top of it: the tab
     * bar hook runs while the activity is still being laid out.
     */
    private static final long SHOW_DELAY_MS = 800L;

    private static boolean checked;

    private FollowPrompt() {}

    /**
     * Show the card if this install/update has not had it yet, then run
     * {@code then} once it is dismissed. When there is nothing to show (or it
     * cannot be shown), {@code then} runs straight away. Runs at most once per
     * process.
     */
    public static void maybeShow(final Context context, final Runnable then) {
        if (context == null || checked) return;
        checked = true;
        final long updateTime = lastUpdateTime(context);
        if (updateTime == 0L || updateTime == Config.getFollowPromptShownFor()) {
            then.run();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!show(context, updateTime, then)) then.run();
        }, SHOW_DELAY_MS);
    }

    private static long lastUpdateTime(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Build and show the card. Returns false if it could not be put on screen. */
    private static boolean show(final Context context, long updateTime, final Runnable then) {
        try {
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                if (activity.isFinishing() || activity.isDestroyed()) return false;
            }

            final Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout frame = Settings.cardFrame(context);
            LinearLayout card = Settings.addCard(context, frame);

            TextView title = new TextView(context);
            title.setText("Follow Feurstagram");
            Settings.titleLarge(title);
            card.addView(title);

            ScrollView bodyScroll = new ScrollView(context);
            bodyScroll.setVerticalScrollBarEnabled(false);
            TextView body = new TextView(context);
            body.setText("Follow @feurstagram_official on Instagram to get a story every time "
                    + "a new version is out: new features, fixes and Instagram updates.");
            Settings.body(body);
            body.setPadding(0, Settings.dp(context, 10), 0, 0);
            bodyScroll.addView(body);
            card.addView(bodyScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            Settings.boundToFrame(frame, card, bodyScroll);

            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonsLp.setMargins(0, Settings.dp(context, 24), 0, 0);
            card.addView(buttons, buttonsLp);

            Button later = Settings.makeButton(context, "Not now", 0, Settings.ON_SURFACE, false);
            later.setBackground(Settings.outlined(context, Settings.OUTLINE_VARIANT));
            later.setOnClickListener(v -> dialog.dismiss());
            buttons.addView(later, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            View spacer = new View(context);
            buttons.addView(spacer, new LinearLayout.LayoutParams(Settings.dp(context, 8), 1));

            Button follow = Settings.makeButton(context, "Follow", Settings.PRIMARY, Settings.ON_PRIMARY, true);
            follow.setOnClickListener(v -> {
                dialog.dismiss();
                openProfile(context);
            });
            buttons.addView(follow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Every way out (a button, Back, a tap outside) continues to "What's new".
            dialog.setOnDismissListener(d -> then.run());

            dialog.setContentView(frame);
            dialog.setCanceledOnTouchOutside(true);
            Settings.styleWindow(dialog, 0, 0.6f);
            dialog.show();
            // Recorded once it is actually on screen, so a launch that dies before
            // this point asks again next time.
            Config.setFollowPromptShownFor(updateTime);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void openProfile(Context context) {
        Uri uri = Uri.parse(PROFILE_URL);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage(context.getPackageName());
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return;
        } catch (Throwable ignored) {
            // No handler inside this package: fall through to any app that can.
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }
}
