package com.feurstagram.extension;

/** Feurstagram-only release identity; never changes Instagram's package or wire version. */
final class ReleaseVersion {
    private static final String REVISED_BASE = "455.0.0.51.72";
    private static final String REVISED_RELEASE = "455.0.0.51.72.1";

    private ReleaseVersion() {}

    static String forInstagram(String base) {
        // Future stock bases keep their normal release identity until revised.
        return REVISED_BASE.equals(base) ? REVISED_RELEASE : base;
    }
}
