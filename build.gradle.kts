plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9.0 ships built-in Kotlin; the plugin jar is provided via classpath so the
        // Compose compiler plugin and Kotlin stdlib resolve for all subprojects.
        classpath(kotlin("gradle-plugin", "2.4.10"))
    }
}

// ── ktlint (style enforcement) ─────────────────────────────────────
// Runs the ktlint CLI directly (no Gradle plugin), so it is independent of AGP /
// Kotlin plugin versions. No baseline: the whole repo must pass, so new code is
// clean and edits never shift stale baseline line numbers (which used to break
// CI on every touch of an old file).
val ktlintVersion = "1.5.0"
val ktlintConfig: Configuration by configurations.creating {
    // ktlint-cli ships a shadow (fat) jar; select it explicitly or Gradle cannot
    // choose between the thin runtimeElements and shadowRuntimeElements variants.
    attributes {
        attribute(Attribute.of("org.gradle.dependency.bundling", String::class.java), "shadowed")
    }
}

dependencies {
    ktlintConfig("com.pinterest.ktlint:ktlint-cli:$ktlintVersion")
}

tasks.register<JavaExec>("ktlint") {
    group = "verification"
    description = "Check Kotlin code style with ktlint (no baseline)"
    classpath = ktlintConfig
    mainClass.set("com.pinterest.ktlint.Main")
    args(
        "app/src/main/**/*.kt",
        "app/src/test/**/*.kt",
        "buildSrc/src/main/**/*.kt",
        "buildSrc/src/test/**/*.kt",
    )
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Auto-format Kotlin code style with ktlint"
    classpath = ktlintConfig
    mainClass.set("com.pinterest.ktlint.Main")
    args(
        "-F",
        "app/src/main/**/*.kt",
        "app/src/test/**/*.kt",
        "buildSrc/src/main/**/*.kt",
        "buildSrc/src/test/**/*.kt",
    )
}
