import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    // Version comes from the settings plugin, which already has IPGP on the classpath.
    id("org.jetbrains.intellij.platform")
}

group = "com.github.dtretyakov.pebble"
version = providers.gradleProperty("pluginVersion").get()

/**
 * The oldest IDE the plugin promises to run in, spelled as a version rather than a build number.
 *
 * It has to agree with `sinceBuild` below: that is the promise, and this is the IDE the promise is
 * checked against with `-PverifySince`.
 */
val OLDEST_SUPPORTED_IDE = "2026.1"

/** The same fact as a build number, which is the only spelling `sinceBuild` accepts. */
val OLDEST_SUPPORTED_BUILD =
    OLDEST_SUPPORTED_IDE.split(".").let { (year, release) -> year.takeLast(2) + release }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.ideaVersion)
        plugin("com.redhat.devtools.lsp4ij:${libs.versions.lsp4ij.get()}")
        // JSON PSI for package.json support; an optional dependency in plugin.xml.
        bundledPlugin("com.intellij.modules.json")
        // The IDE's test runner, for UI test scenarios.
        bundledModule("intellij.platform.testRunner")
        bundledModule("intellij.platform.smRunner")
        // The IDE's MCP server, for the tools agents call; an optional dependency in plugin.xml.
        bundledPlugin("com.intellij.mcpServer")
        // In tests, the IDE as most users have it: package.json already has npm's schema.
        testBundledPlugins("JavaScript", "NodeJS")
        pluginVerifier()
        // Gives the tests a real Project, so the parts that only exist inside an IDE can be
        // exercised rather than only compiled.
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Debugger)
    }

    testImplementation(libs.junitJupiter)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junitPlatformLauncher)
    testRuntimeOnly(libs.junitVintage)
}

/**
 * The self-check's own source set, so it can be a plugin of its own rather than part of this one.
 *
 * What it checks is this plugin from the inside, so it compiles against everything the plugin does;
 * what it uses to do that is internal API, which is why it cannot ship in the plugin. It is
 * packaged as a jar plugin by `selfCheckJar` and installed by `runSelfCheck` alone, so it never
 * reaches `buildPlugin`'s archive or the Plugin Verifier. See SelfCheckStarter.
 */
val selfCheck: SourceSet = sourceSets.create("selfCheck") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}

kotlin {
    // Associated with `main`, so the check may look at what the plugin keeps `internal`.
    target.compilations.named("selfCheck") {
        associateWith(target.compilations.getByName("main"))
    }
}

val selfCheckJar = tasks.register<Jar>("selfCheckJar") {
    archiveBaseName = "pebble-selfcheck"
    destinationDirectory = layout.buildDirectory.dir("selfCheck")
    // The classes from the build, the resources from source: the platform plugin replaces
    // META-INF/plugin.xml in every processResources output with Pebble's own, patched, and a
    // second plugin carrying the first one's descriptor is a duplicate the IDE refuses to load.
    from(selfCheck.output.classesDirs)
    from(selfCheck.resources.srcDirs)
}

/**
 * The agent skill has one copy, `agents/plugin/skills`, where Claude Code's marketplace installs it
 * from; the plugin carries the same files, to write into a project.
 */
tasks.named<ProcessResources>("processResources") {
    from("agents/plugin/skills") { into("agent-skills") }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The live tests drive a real Pebble SDK; they opt in rather than out so a clean checkout on a
    // machine without one still goes green. A Gradle property rather than only an environment
    // variable, because a test JVM inherits the *daemon's* environment and not the one the
    // developer typed the command in.
    systemProperty(
        "pebble.liveTests",
        providers.gradleProperty("liveTests")
            // `-PliveTests` with no value is the natural way to type it, and arrives as "".
            .map { it.ifEmpty { "true" } }
            .orElse(providers.environmentVariable("PEBBLE_LIVE_TESTS").map { if (it == "1") "true" else it })
            .getOrElse("false"),
    )
    testLogging {
        showStandardStreams = providers.gradleProperty("showOutput").isPresent
    }
}

intellijPlatform {
    pluginVerification {
        // Internal API is not in the exceptions: the Marketplace refuses a plugin that uses any,
        // so it fails here first.
        failureLevel = FailureLevel.ALL - setOf(
            FailureLevel.DEPRECATED_API_USAGES,
            FailureLevel.EXPERIMENTAL_API_USAGES,
        )

        ides {
            // The default is the IDE the plugin is compiled against: nothing extra to download.
            // The others are one IDE each, opted into by a property:
            //
            //     ./gradlew verifyPlugin -PverifySince        the oldest IDE sinceBuild promises
            //     ./gradlew verifyPlugin -PverifyClion        the newest CLion
            //     ./gradlew verifyPlugin -PverifyWebStorm     the newest WebStorm
            //     ./gradlew verifyPlugin -PverifyEap          the next IDEA, before it ships
            //     ./gradlew verifyPlugin -PverifyRecommended  every IDE JetBrains recommends
            //     ./gradlew verifyPlugin -PverifyAgainst=<unpacked IDE>
            //
            // CLion and WebStorm are named targets of this plugin rather than IDEs it happens to
            // load in, and it is compiled against neither: the plugin asks only for the platform,
            // and these two checks are what make that claim more than a hope.
            val unpacked = providers.gradleProperty("verifyAgainst").orNull?.takeIf { it.isNotBlank() }
            when {
                unpacked != null -> local(file(unpacked))
                providers.gradleProperty("verifyRecommended").isPresent -> recommended()
                providers.gradleProperty("verifySince").isPresent ->
                    create(IntelliJPlatformType.IntellijIdeaUltimate, OLDEST_SUPPORTED_IDE)
                providers.gradleProperty("verifyClion").isPresent -> latest {
                    types = listOf(IntelliJPlatformType.CLion)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
                providers.gradleProperty("verifyWebStorm").isPresent -> latest {
                    types = listOf(IntelliJPlatformType.WebStorm)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
                providers.gradleProperty("verifyEap").isPresent -> latest {
                    types = listOf(IntelliJPlatformType.IntellijIdeaUltimate)
                    channels = listOf(ProductRelease.Channel.EAP)
                }
                else -> current()
            }
        }
    }

    pluginConfiguration {
        id = "com.github.dtretyakov.pebble"
        // Pebble is Core Devices' trademark, which a name may only refer to ("… for Pebble"); the
        // Marketplace's "Pebble" is another plugin's, for Pebble Templates.
        name = "Develop for Pebble"
        version = project.version.toString()

        // The top section of CHANGELOG.md, so the release notes are written once and in the place
        // a reader of the repository looks for them.
        changeNotes = provider { latestChangeNotes(file("CHANGELOG.md")) }

        ideaVersion {
            sinceBuild = OLDEST_SUPPORTED_BUILD
            // Deliberately open-ended: the plugin uses no unstable platform API,
            // and pinning it would break every IDE upgrade for no reason.
            untilBuild = provider { null }
        }
    }

    /**
     * The signature the IDE checks when the plugin is installed. The certificate is the author's
     * and lives nowhere in this repository.
     */
    signing {
        // As a file, though it arrives as content: given as content, the platform plugin hands the
        // chain to `verifyPluginSignature` twice and the zip signer rejects the second copy.
        certificateChainFile = providers.environmentVariable("CERTIFICATE_CHAIN").map { chain ->
            layout.buildDirectory.file("signing/certificate-chain.pem").get().also {
                it.asFile.parentFile.mkdirs()
                it.asFile.writeText(chain)
            }
        }
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // A pre-release goes to a channel of its own, so it reaches the people who added that
        // channel and nobody else.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
}

/** `verifyPluginSignature` reads the archive `signPlugin` writes, and nothing else says so. */
tasks.named("verifyPluginSignature") {
    dependsOn(tasks.named("signPlugin"))
}

intellijPlatformTesting {
    /**
     * A headless IDE that checks the plugin is whole: extensions registered, actions in their
     * menus, the SDK reachable. See SelfCheckStarter for why a unit test cannot answer any of that.
     *
     *     ./gradlew runSelfCheck
     */
    runIde.register("runSelfCheck") {
        // Copied in rather than declared with `plugins { localPlugin(…) }`, which reads the jar
        // while Gradle is still planning the build — before `selfCheckJar` has run.
        prepareSandboxTask {
            from(selfCheckJar) { into("pebble-selfcheck/lib") }
        }
        task {
            args = listOf("pebbleSelfCheck")
            jvmArgumentProviders.add(
                CommandLineArgumentProvider { listOf("-Djava.awt.headless=true", "-Didea.is.internal=true") },
            )
        }
    }

    /**
     * A sandbox IDE with a Pebble project already open.
     *
     *     ./gradlew runIdeWithFixture
     *     ./gradlew runIdeWithFixture -Pfixture=/path/to/project
     */
    runIde.register("runIdeWithFixture") {
        task {
            // A fresh copy under `build`, not the fixture itself: the IDE writes `.idea/` into the
            // project it opens, and the fixture lives in the repository.
            val scratch = layout.buildDirectory.dir("fixture-c").get().asFile
            val chosen = providers.gradleProperty("fixture").getOrElse(scratch.absolutePath)
            if (chosen == scratch.absolutePath) {
                doFirst {
                    val source = layout.projectDirectory.dir("src/test/resources/fixture-c").asFile
                    scratch.deleteRecursively()
                    source.copyRecursively(scratch)
                }
            }
            args = listOf(chosen)
        }
    }
}

/**
 * The most recent section of a keep-a-changelog file, as the HTML the Marketplace renders.
 *
 * Only what the plugin descriptor allows: headings, lists and inline code. A Markdown library for
 * four constructs would be a dependency for the sake of one string.
 */
fun latestChangeNotes(changelog: File): String {
    if (!changelog.exists()) return ""

    val lines = changelog.readLines()
    val start = lines.indexOfFirst { it.startsWith("## ") }
    if (start < 0) return ""
    val end = lines.drop(start + 1).indexOfFirst { it.startsWith("## " ) }
        .let { if (it < 0) lines.size else start + 1 + it }

    val html = StringBuilder()
    var inList = false
    // Closed explicitly. A browser forgives an unclosed `<li>`; the Marketplace runs the change
    // notes through a sanitiser of its own, which is stricter than the plugin descriptor's DTD and
    // has no reason to be as forgiving.
    var openItem = false

    fun closeItem() {
        if (openItem) {
            html.append("</li>")
            openItem = false
        }
    }

    fun closeList() {
        closeItem()
        if (inList) {
            html.append("</ul>")
            inList = false
        }
    }

    // A bullet may be wrapped over several lines; each continuation belongs to the item above it.
    lines.subList(start + 1, end).forEach { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("### ") -> {
                closeList()
                html.append("<h4>").append(inlineHtml(line.removePrefix("### "))).append("</h4>")
            }
            line.startsWith("- ") -> {
                closeItem()
                if (!inList) {
                    html.append("<ul>")
                    inList = true
                }
                html.append("<li>").append(inlineHtml(line.removePrefix("- ")))
                openItem = true
            }
            inList -> html.append(' ').append(inlineHtml(line))
            else -> html.append("<p>").append(inlineHtml(line)).append("</p>")
        }
    }
    closeList()
    return html.toString()
}

fun inlineHtml(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
