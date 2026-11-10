group = "com.feurstagram"

patches {
    about {
        name = "Feurstagram Patches"
        description = "Distraction-free Instagram: blocks the feed, stories, explore, reels, ads and suggestions, with a runtime toggle and an optional permanent lock."
        source = "https://github.com/jean-voila/FeurStagram"
        author = "Jean Herail"
        contact = "feurstagram@gmail.com"
        website = "https://jean-voila.github.io/FeurStagram/"
        license = "GPLv3"
    }
}

dependencies {
    // Provides app.morphe.util.* helpers (DOM, bytecode, references) used by the patches.
    implementation(libs.morphe.patches.library)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}
