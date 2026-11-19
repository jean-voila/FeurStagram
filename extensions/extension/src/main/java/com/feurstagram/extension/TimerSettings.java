package com.feurstagram.extension;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The "Unlock timer" page of the settings: whether the timer is on, the session
 * (start, pause, resume), which blocked surfaces it opens, how much time, and how
 * often the allowance refills. Built with {@link Settings}' rows so it looks like
 * every other page. See {@link Timer} for the rules.
 */
final class TimerSettings {

    private TimerSettings() {}

    private static final int[] PRESET_HOURS = {1, 2, 5, 8, 12, 24};

    /** The one-line summary of the overview row. */
    static String summary() {
        if (!Timer.isEnabled()) return "Off";
        String left = Timer.formatLong(Timer.remainingMs()) + " left";
        switch (Timer.state()) {
            case Timer.RUNNING: return "Unlocked now, " + left;
            case Timer.PAUSED: return "Paused, " + left;
            default:
                return Timer.formatLong(Timer.quotaMinutes() * 60_000L)
                        + (Timer.WEEK.equals(Timer.refill()) ? " a week" : " a day") + ", " + left;
        }
    }

    static void build(Context context, LinearLayout column) {
        column.removeAllViews();
        Runnable rebuild = () -> build(context, column);

        if (Config.isHardcoreMode()) {
            TextView note = new TextView(context);
            note.setText("Permanent lock is on: the timer can only be narrowed (fewer surfaces, less time, "
                    + "a slower refill).");
            Settings.body(note);
            note.setPadding(Settings.dp(context, 4), Settings.dp(context, 12), Settings.dp(context, 4), 0);
            column.addView(note);
        }

        LinearLayout main = Settings.addGroup(context, column);
        switchRow(context, main, "Unlock timer",
                "A time allowance during which the surfaces you pick work again. Each start, pause, "
                        + "resume or skip restarts Instagram to apply it.",
                Timer.isEnabled(), on -> {
                    if (!Timer.setEnabled(on)) return false;
                    if (!on && Timer.hasSession()) Config.setNeedsRestart();
                    rebuild.run();
                    return true;
                });
        Settings.sealGroup(context, main);
        if (!Timer.isEnabled()) return;

        buildSession(context, column);

        // --- Surfaces ---
        LinearLayout surfaces = Settings.addSection(context, column, "Unlock during a session");
        for (String key : Settings.SURFACE_KEYS) {
            boolean blocked = Config.storedBlocked(key);
            View row = switchRow(context, surfaces, Settings.surfaceLabel(key), blocked ? null : "Not blocked",
                    blocked && Timer.isPicked(key), on -> {
                        if (!Timer.setPicked(key, on)) return false;
                        if (Timer.isRunning()) Config.setNeedsRestart();
                        return true;
                    });
            if (!blocked) Settings.greyOut(row);
        }
        Settings.sealGroup(context, surfaces);

        // --- Allowance ---
        Settings.addSectionLabel(context, column, "Time allowance");
        column.addView(durationChips(context, rebuild));

        // --- Refill ---
        LinearLayout refill = Settings.addSection(context, column, "Refill");
        radioRow(context, refill, "Every day", "At midnight", Timer.DAY.equals(Timer.refill()), () -> {
            if (!Timer.setRefill(Timer.DAY)) lockedToast(context);
            rebuild.run();
        });
        radioRow(context, refill, "Every week", "On Monday at midnight", Timer.WEEK.equals(Timer.refill()), () -> {
            if (!Timer.setRefill(Timer.WEEK)) lockedToast(context);
            rebuild.run();
        });
        Settings.sealGroup(context, refill);
    }

    /** The current allowance and the session button. */
    private static void buildSession(Context context, LinearLayout column) {
        LinearLayout group = Settings.addSection(context, column, "Session");
        String period = Timer.WEEK.equals(Timer.refill()) ? "This week" : "Today";
        String state = Timer.state();
        String status;
        switch (state) {
            case Timer.RUNNING: status = "Unlocked now"; break;
            case Timer.PAUSED: status = "Paused"; break;
            case Timer.SKIPPED: status = "Skipped until the next refill"; break;
            default: status = "Not started"; break;
        }
        Settings.makeRow(context, group, period + ": " + Timer.formatLong(Timer.remainingMs()) + " left of "
                + Timer.formatLong(Timer.quotaMinutes() * 60_000L), status);
        Settings.sealGroup(context, group);

        String action = null;
        Runnable onTap = null;
        if (Timer.RUNNING.equals(state)) {
            action = "Pause session";
            onTap = () -> { if (Timer.pause()) CacheCleaner.clearAndRestart(context); };
        } else if (Timer.PAUSED.equals(state) && Timer.remainingMs() > 0) {
            action = "Resume session";
            onTap = () -> { if (Timer.start()) CacheCleaner.clearAndRestart(context); };
        } else if (Timer.IDLE.equals(state) && Timer.remainingMs() > 0) {
            action = "Start session now";
            onTap = () -> {
                if (!Timer.hasPickedSurface()) {
                    Toast.makeText(context, "Pick at least one blocked surface to unlock", Toast.LENGTH_LONG).show();
                } else if (Timer.start()) {
                    CacheCleaner.clearAndRestart(context);
                }
            };
        }
        if (action != null) {
            Button button = Settings.makeButton(context, action, Settings.PRIMARY, Settings.ON_PRIMARY, true);
            Runnable tap = onTap;
            button.setOnClickListener(v -> tap.run());
            column.addView(button, Settings.stackedButtonParams(context, 12));
        }
    }

    /** 1h 2h 5h 8h / 12h 24h Custom, the current one filled. */
    private static View durationChips(Context context, Runnable rebuild) {
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        int quota = Timer.quotaMinutes();
        boolean preset = false;
        LinearLayout line = null;
        for (int i = 0; i <= PRESET_HOURS.length; i++) {
            if (i % 4 == 0) {
                line = new LinearLayout(context);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lineLp.topMargin = Settings.dp(context, i == 0 ? 8 : 8);
                grid.addView(line, lineLp);
            }
            final boolean custom = i == PRESET_HOURS.length;
            final int minutes = custom ? 0 : PRESET_HOURS[i] * 60;
            boolean selected = custom ? !preset : quota == minutes;
            if (!custom && selected) preset = true;
            String text = custom ? (selected ? "Custom · " + Timer.formatLong(quota * 60_000L) : "Custom")
                    : PRESET_HOURS[i] + "h";
            Button chip = Settings.makeButton(context, text,
                    selected ? Settings.PRIMARY : Settings.SURFACE_CONTAINER_HIGH,
                    selected ? Settings.ON_PRIMARY : Settings.ON_SURFACE, true);
            chip.setOnClickListener(v -> {
                if (custom) {
                    customDialog(context, rebuild);
                } else {
                    if (!Timer.setQuotaMinutes(minutes)) lockedToast(context);
                    rebuild.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    custom ? 2f : 1f);
            if (line.getChildCount() > 0) lp.leftMargin = Settings.dp(context, 8);
            line.addView(chip, lp);
        }
        return grid;
    }

    /** Hours and minutes, for an allowance that is not a preset. */
    private static void customDialog(Context context, Runnable rebuild) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout frame = Settings.cardFrame(context);
        LinearLayout card = Settings.addCard(context, frame);
        TextView title = new TextView(context);
        title.setText("Custom allowance");
        Settings.titleLarge(title);
        card.addView(title);

        LinearLayout pickers = new LinearLayout(context);
        pickers.setOrientation(LinearLayout.HORIZONTAL);
        pickers.setGravity(Gravity.CENTER);
        int quota = Timer.quotaMinutes();
        NumberPicker hours = picker(context, 0, 24, Math.min(24, quota / 60));
        String[] steps = new String[12];
        for (int i = 0; i < 12; i++) steps[i] = (i * 5 < 10 ? "0" : "") + i * 5;
        NumberPicker minutes = picker(context, 0, 11, (quota % 60) / 5);
        minutes.setDisplayedValues(steps);
        pickers.addView(hours);
        pickers.addView(label(context, "h"));
        pickers.addView(minutes);
        pickers.addView(label(context, "min"));
        LinearLayout.LayoutParams pickersLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickersLp.topMargin = Settings.dp(context, 12);
        card.addView(pickers, pickersLp);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsLp.topMargin = Settings.dp(context, 20);
        card.addView(buttons, buttonsLp);
        Button cancel = Settings.makeButton(context, "Cancel", 0, Settings.ON_SURFACE, false);
        cancel.setBackground(Settings.outlined(context, Settings.OUTLINE_VARIANT));
        cancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View spacer = new View(context);
        buttons.addView(spacer, new LinearLayout.LayoutParams(Settings.dp(context, 8), 1));
        Button ok = Settings.makeButton(context, "Set", Settings.PRIMARY, Settings.ON_PRIMARY, true);
        ok.setOnClickListener(v -> {
            int total = Math.min(24 * 60, hours.getValue() * 60 + minutes.getValue() * 5);
            dialog.dismiss();
            if (total <= 0) return;
            if (!Timer.setQuotaMinutes(total)) lockedToast(context);
            rebuild.run();
        });
        buttons.addView(ok, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dialog.setContentView(frame);
        dialog.setCanceledOnTouchOutside(true);
        Settings.styleWindow(dialog, 0, 0.6f);
        dialog.show();
    }

    private static NumberPicker picker(Context context, int min, int max, int value) {
        NumberPicker picker = new NumberPicker(context);
        picker.setMinValue(min);
        picker.setMaxValue(max);
        picker.setValue(Math.max(min, Math.min(max, value)));
        picker.setTextColor(Settings.ON_SURFACE);
        return picker;
    }

    private static TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        Settings.body(view);
        view.setPadding(Settings.dp(context, 6), 0, Settings.dp(context, 16), 0);
        return view;
    }

    private interface Toggle {
        /** False refuses the change, and the switch goes back. */
        boolean set(boolean on);
    }

    private static View switchRow(Context context, LinearLayout parent, String label, String support,
                                  boolean value, Toggle onChange) {
        LinearLayout row = Settings.makeRow(context, parent, label, support);
        ExpressiveSwitch toggle = new ExpressiveSwitch(context);
        toggle.setChecked(value);
        toggle.setContentDescription(label);
        toggle.setOnCheckedChangeListener((button, on) -> {
            if (!onChange.set(on)) {
                lockedToast(context);
                button.setChecked(!on);
            }
        });
        row.addView(toggle);
        row.setOnClickListener(v -> {
            if (toggle.isEnabled()) toggle.toggle();
        });
        return row;
    }

    private static void radioRow(Context context, LinearLayout parent, String label, String support,
                                 boolean checked, Runnable onTap) {
        LinearLayout row = Settings.makeRow(context, parent, label, support);
        RadioButton mark = new RadioButton(context);
        mark.setClickable(false);
        mark.setFocusable(false);
        mark.setChecked(checked);
        mark.setButtonTintList(Settings.buildStateList(Settings.PRIMARY, Settings.OUTLINE));
        row.addView(mark);
        row.setOnClickListener(v -> { if (!checked) onTap.run(); });
    }

    private static void lockedToast(Context context) {
        Toast.makeText(context, "Permanent lock: the timer can only be narrowed", Toast.LENGTH_SHORT).show();
    }
}
