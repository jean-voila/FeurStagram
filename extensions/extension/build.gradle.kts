extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "com.feurstagram.extension"
}

dependencies {
    testImplementation(libs.junit)
    // Android's org.json is only a stub in local unit tests; the real library
    // parses the release JSON there.
    testImplementation(libs.json)
}
