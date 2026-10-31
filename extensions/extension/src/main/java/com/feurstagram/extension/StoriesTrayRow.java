package com.feurstagram.extension;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * The stories row of the Direct inbox in Quoicouview mode: a row type of
 * Instagram's own list adapter ({@code IgRecyclerViewAdapter}), like the Notes
 * tray row or a thread.
 *
 * Instagram's row types extend an obfuscated "item definition" base class
 * (create a view holder, name the model class it shows, bind a model to a
 * holder). This class cannot name it at compile time, so the Quoicouview patch
 * makes it a subclass of that base when patching and adds the three methods
 * under the base's obfuscated names, each calling into {@link Quoicouview}:
 * {@code createStoriesRow} (in a {@link Holder}), the {@link Model} class,
 * {@code bindStoriesRow}.
 * Likewise {@link Model} is made to implement Instagram's list-model interface,
 * whose obfuscated content-equality method is bridged to
 * {@link Model#sameContentAs}.
 */
public final class StoriesTrayRow {

    public StoriesTrayRow() {}

    /** The one model of the row: there is a single stories tray. */
    public static final class Model {
        static final Model INSTANCE = new Model();

        private Model() {}

        /** Identity of the row for the adapter's list diffing. */
        public Object getKey() {
            return "feurstagram_stories_tray";
        }

        /** The row's content never changes from the list's point of view: the tray updates itself. */
        public boolean sameContentAs(Object other) {
            return other instanceof Model;
        }
    }

    /**
     * The row's view holder. Instagram's holder base class is abstract and
     * obfuscated: the patch makes this its subclass and gives it a constructor
     * taking the row's view, as Instagram's own holders have.
     */
    public static final class Holder {
        private Holder() {}
    }

    /** The row's view: a frame the stories tray is placed in. */
    static final class Host extends FrameLayout {
        Host(Context context) {
            super(context);
        }
    }
}
