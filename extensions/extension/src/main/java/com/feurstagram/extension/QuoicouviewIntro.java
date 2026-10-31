package com.feurstagram.extension;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/**
 * A one-time card presenting Quoicouview mode, with a screenshot of it, shown
 * after the update that brings it: between the "follow the official account"
 * card and "What's new". "Turn it on" switches the mode on and restarts the app
 * the way the settings' Done does; anything else leaves it off, and the switch
 * stays first in the settings.
 *
 * The screenshot is not in the extension (which is code only): the Quoicouview
 * assets patch adds it to the APK's assets. Without it the card simply shows no
 * picture.
 *
 * Skipped on a fresh install, where the first-run guide to the settings comes
 * first (it appears on a later launch instead), when the mode is already on,
 * and when the permanent lock rules it out.
 */
public final class QuoicouviewIntro {

    /** Path inside the APK's assets, written by the Quoicouview assets patch. */
    private static final String SCREENSHOT = "feurstagram/quoicouview.jpg";

    /** Let the window behind settle, as for the other launch cards. */
    private static final long SHOW_DELAY_MS = 400L;

    /** Most of the screen the screenshot may take before it scales down. */
    private static final float SCREENSHOT_MAX_SCREEN_FRACTION = 0.5f;

    private QuoicouviewIntro() {}

    /** Show the card if it is due, then run {@code then} once it is gone (or straight away). */
    public static void maybeShow(final Context context, final Runnable then) {
        if (context == null || !isDue()) {
            then.run();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!show(context, then)) then.run();
        }, SHOW_DELAY_MS);
    }

    private static boolean isDue() {
        return !Config.isQuoicouviewIntroShown()
                && Config.isOnboardingDone()
                && !Config.isQuoicouviewEnabled()
                && Config.isQuoicouviewAvailable();
    }

    /** Development hook: show the card now, whether or not it is due. */
    static void showNow(Context context) {
        show(context, () -> {});
    }

    private static boolean show(final Context context, final Runnable then) {
        try {
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                if (activity.isFinishing() || activity.isDestroyed()) return false;
            }

            final Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout frame = Settings.cardFrame(context);
            LinearLayout card = Settings.addCard(context, frame);

            TextView badge = new TextView(context);
            badge.setText("Experimental feature");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            badge.setTextColor(Settings.ON_SURFACE);
            badge.setBackground(Settings.roundedRect(Settings.SURFACE_CONTAINER_HIGH, 100f, context));
            int padH = Settings.dp(context, 10);
            int padV = Settings.dp(context, 4);
            badge.setPadding(padH, padV, padH, padV);
            card.addView(badge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView title = new TextView(context);
            title.setText("Quoicouview mode");
            Settings.titleLarge(title);
            title.setPadding(0, Settings.dp(context, 12), 0, 0);
            card.addView(title);

            TextView body = new TextView(context);
            body.setText("Make Messages your main page, with the stories of the people you follow "
                    + "on top. Home is turned off. You can switch it at any time, first thing in "
                    + "the Feurstagram settings.");
            Settings.body(body);
            body.setPadding(0, Settings.dp(context, 10), 0, 0);
            card.addView(body);

            ImageView screenshot = screenshot(context);
            if (screenshot != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, Settings.dp(context, 20), 0, 0);
                card.addView(screenshot, lp);
                // The picture is what gives way on a short screen, never the buttons.
                Settings.boundToFrame(frame, card, screenshot);
            }

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

            final boolean[] turnedOn = {false};
            Button enable = Settings.makeButton(context, "Turn it on", Settings.PRIMARY, Settings.ON_PRIMARY, true);
            enable.setOnClickListener(v -> {
                turnedOn[0] = true;
                Config.setBlocked("quoicouview_mode", true);
                dialog.dismiss();
                CacheCleaner.clearAndRestart(context);
            });
            buttons.addView(enable, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // Any other way out carries on to "What's new"; turning the mode on
            // restarts the app, and the next launch shows it instead.
            dialog.setOnDismissListener(d -> {
                if (!turnedOn[0]) then.run();
            });

            dialog.setContentView(frame);
            dialog.setCanceledOnTouchOutside(true);
            Settings.styleWindow(dialog, 0, 0.6f);
            dialog.show();
            // Recorded once on screen, so a launch that dies first asks again.
            Config.setQuoicouviewIntroShown();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The bundled screenshot, rounded and bounded, or null if the APK has none. */
    private static ImageView screenshot(Context context) {
        Bitmap bitmap;
        try (InputStream in = context.getAssets().open(SCREENSHOT)) {
            bitmap = BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            return null;
        }
        if (bitmap == null) return null;

        ImageView image = new ImageView(context);
        image.setImageBitmap(bitmap);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setMaxHeight((int) (context.getResources().getDisplayMetrics().heightPixels
                * SCREENSHOT_MAX_SCREEN_FRACTION));
        image.setContentDescription("Instagram in Quoicouview mode: stories on top of Messages");
        // Round the picture's own corners, whatever size it ends up drawn at.
        final float radius = Settings.dp(context, 16);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                ImageView iv = (ImageView) view;
                android.graphics.drawable.Drawable d = iv.getDrawable();
                if (d == null) return;
                // The drawn picture, centred and scaled to fit, not the whole view.
                float scale = Math.min((float) view.getWidth() / d.getIntrinsicWidth(),
                        (float) view.getHeight() / d.getIntrinsicHeight());
                int w = Math.round(d.getIntrinsicWidth() * scale);
                int h = Math.round(d.getIntrinsicHeight() * scale);
                int left = (view.getWidth() - w) / 2;
                int top = (view.getHeight() - h) / 2;
                outline.setRoundRect(left, top, left + w, top + h, radius);
            }
        });
        image.setClipToOutline(true);
        return image;
    }
}
