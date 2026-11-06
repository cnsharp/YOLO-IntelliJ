import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("org.jetbrains.intellij.platform")
    kotlin("jvm") version "2.3.20"
}

/**
 * The single source of truth for the product name is `plugin.name` in
 * messages/YoloBundle.properties. We read it here and feed it to pluginConfiguration.name
 * (which overrides plugin.xml's <name>), so UI text, the Marketplace name, and the Settings
 * page title all come from one place — renaming only requires editing a single line.
 */
val bundleFile = file("src/main/resources/messages/YoloBundle.properties")
val productName: String = Properties().apply {
    bundleFile.inputStream().use { load(it) }
}.getProperty("plugin.name")
    ?: error("plugin.name missing from ${bundleFile.path}")

/**
 * The single source of truth for the version is `pluginVersion` in gradle.properties
 * (CI can override it with -PpluginVersion=x). It must be assigned to project.version:
 * Gradle's archive tasks take archiveVersion from it by default. Previously only
 * pluginConfiguration.version was set, leaving project.version at "unspecified", so the
 * distributed artifact degenerated into a version-less yolo.zip.
 */
version = providers.gradleProperty("pluginVersion").get()

intellijPlatform {
    // Use the locally installed IDEA as the SDK for offline builds (no IntelliJ SDK download).
    pluginConfiguration {
        id = "com.cnsharp.yolo"
        name = productName
        version = project.version.toString()
        vendor {
            name = "CnSharp Studio"
            email = "support@cnsharp.com"
        }
        ideaVersion {
            // Backward-compatible down to 2023.3: the panel uses only public JediTerm/PTY4J + IntelliJ
            // public APIs (no internal Terminal API). The few version-sensitive calls use their oldest
            // still-present forms (ReadAction.run, TerminalColor(int), OpenFileDescriptor 4-arg,
            // ContentFactory.getInstance(), FilenameIndex.getVirtualFilesByName(name, scope)).
            sinceBuild = "233"
        }
    }
    // Verify the built artifact against the whole IntelliJ-platform family, not just IDEA. The panel
    // installs on every one of these (it depends only on the lang module, present everywhere). Run
    // explicitly via `./gradlew verifyPlugin` — this task is NOT part of the default `build`.
    // `pluginVerification` is a member of `IntelliJPlatformExtension` (a sibling of `pluginConfiguration`),
    // so it must live directly inside `intellijPlatform { }`, not nested in `pluginConfiguration { }`.
    // The verifier's `Ides` block supports `create(type, version)` but not `recommended()`, so each
    // target IDE is listed explicitly.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.PyCharmCommunity, "2023.3")
            create(IntelliJPlatformType.GoLand, "2023.3")
            create(IntelliJPlatformType.WebStorm, "2023.3")
            create(IntelliJPlatformType.RubyMine, "2023.3")
            create(IntelliJPlatformType.PhpStorm, "2023.3")
            create(IntelliJPlatformType.CLion, "2023.3")
            create(IntelliJPlatformType.DataGrip, "2023.3")
            create(IntelliJPlatformType.RustRover, "2023.3")
            create(IntelliJPlatformType.Rider, "2023.3")
        }
    }
}

// Run the plugin inside other IDEs to manually confirm type-name / Class.member links work per language.
// Each task downloads its IDE on first run; pass `localPath = file("/Applications/X.app")` to skip the
// download when that IDE is already installed locally.
intellijPlatformTesting {
    runIde {
        register("pyCharm") {
            type = IntelliJPlatformType.PyCharmCommunity
            version = "2023.3"
        }
        register("goLand") {
            type = IntelliJPlatformType.GoLand
            version = "2023.3"
        }
        register("webStorm") {
            type = IntelliJPlatformType.WebStorm
            version = "2023.3"
        }
        register("rider") {
            type = IntelliJPlatformType.Rider
            version = "2023.3"
            // Use the locally installed Rider (offline, no download) — set in Task 2 for manual C#/Rider
            // link testing. When localPath is present it overrides `version` for which IDE is launched.
            localPath = file("/Applications/Rider.app")
        }
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        // JetBrains' official repository: where the IntelliJ Platform and bundled plugins
        // (terminal, etc.) are resolved from.
        defaultRepositories()
        // For local offline builds: resolve bundled plugins from the locally installed IDEA
        // (pairs with local(...) in dependencies below).
        // For local use: uncomment the next line.
        // localPlatformArtifacts()
    }
}

dependencies {
    // When a local IDEA is present, use `local` (offline, no download); if CI/Release
    // doesn't provide that property, fall back to `create` (downloads).
    // Resolution order: -PlocalIdeaPath (CLI) > local.properties (gitignored) > download.
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val localIdeaPath = providers.gradleProperty("localIdeaPath").orNull
        ?: localProps.getProperty("localIdeaPath")

    intellijPlatform {
        if (localIdeaPath != null) {
            local(file(localIdeaPath))
        } else {
            // Build against the oldest supported version (2023.3) on Community so an accidental use of a
            // newer-only or Ultimate-only API fails here instead of at Marketplace review. The panel no
            // longer depends on the Java plugin (type-name navigation goes through the language-agnostic
            // com.intellij.gotoClassContributor EP), so Community is a valid, stricter baseline.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2023.3")
        }
    }

    // PTY4J is in lib/ up to 2026.2, but from 2026.3 (263) it is a separate bundled plugin whose
    // classes live in a module jar under plugins/pty4j-plugin/lib/modules/. Once plugin.xml declares
    // <depends> on that plugin, the platform Gradle plugin resolves the classpath from the dependency
    // list and no longer sweeps this module jar in, so add it explicitly for compilation. compileOnly
    // keeps it out of the distributable: at runtime the IDE provides it (platform lib on <=262, the
    // pty4j plugin on 263+). Empty on older IDEs, where lib/ is already on the classpath.
    if (localIdeaPath != null) {
        compileOnly(fileTree("$localIdeaPath/Contents/plugins/pty4j-plugin/lib/modules") {
            include("intellij.libraries.pty4j.jar")
        })
    }
}

// Unit tests run against the SAME local IDEA platform used for compilation (`intellijPlatformClasspath`),
// which already resolves cleanly. We deliberately do NOT use the plugin's `testFramework(...)` helper:
// on IDEA 2026.2 its `ModuleDescriptorsValueSource` fails to parse the new `namespace` attribute on the
// platform's `<module>` descriptors (plugin 2.18.1 predates that schema), so the IntelliJ test classpath
// cannot be assembled offline. Reusing the working main classpath keeps `:test` fully offline. JUnit 4 is
// pulled once (cached) since release IDEA does not bundle it.
dependencies {
    testImplementation(files(configurations["intellijPlatformClasspath"]))
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    useJUnit()
}

kotlin {
    // Target Java 17 bytecode: IDEA 2023.3 (the minimum we support) runs on JBR 17, so the plugin must
    // not be compiled to a newer class-file version. We still compile *with* the local JBR 25, just
    // emitting Java 17-compatible classes, which also run fine on newer IDEA (2026.2's JBR 25).
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Keep the (empty) Java compile task consistent with the Kotlin target above — otherwise Gradle rejects
// the mixed JVM targets. `release` lets us emit Java 17 class files using the local JBR 25.
tasks.withType<JavaCompile> {
    options.release.set(17)
}

// The IntelliJ Platform Gradle Plugin's instrumentCode task resolves the Gradle daemon JDK and, on a
// foreign JDK, looks for a JBR-only "Packages" directory that no longer exists in IDEA 2026's bundled
// runtime — failing the build with ".../Contents/Home/Packages does not exist". Disabling it only skips
// nullability assertion instrumentation; the plugin still builds and runs correctly.
tasks.named("instrumentCode") {
    enabled = false
}

// buildSearchableOptions launches a sandboxed IDEA to index settings-search metadata and fails in this
// environment with ClassNotFoundException (org.jetbrains.plugins.terminal.agent.TerminalAgent) — a known
// issue unrelated to plugin code. The panel adds no searchable settings, so skipping it is safe and
// unblocks `buildPlugin`.
tasks.named("buildSearchableOptions") {
    enabled = false
}

// Force a clean before packaging. This repo's `build/` is shared between the main `yolo` build and the
// `yolo-exp` build (settings.gradle.kts names the project `yolo-exp`, and both variants compile into the
// same `build/classes/kotlin/main`). Without a clean, a build of one variant repackages the other
// variant's classes into its jar — e.g. `com.cnsharp.yolo.exp.*` leaked into the main `yolo` jar, so the
// same class was loaded by two plugin classloaders and `AgentExtenderSettingsExp.getInstance()` threw
// ClassCastException, taking down the exp settings page. Cleaning first guarantees no stale variant
// classes are ever packaged. Scoped to `buildPlugin` (the distributable), not `jar`, so dev `runIde`
// stays incremental. The same guard must also be present in the exp (`com.cnsharp.yolo.exp`) build.gradle.kts.
tasks.named("buildPlugin") {
    dependsOn("clean")
}
