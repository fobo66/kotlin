import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.crypto.checksum.Checksum
import org.gradle.plugins.ide.idea.model.IdeaModel
import proguard.gradle.ProGuardTask

buildscript {
    val cacheRedirectorEnabled = findProperty("cacheRedirectorEnabled")?.toString()?.toBoolean() == true

    kotlinBootstrapFrom(BootstrapOption.SpaceBootstrap(kotlinBuildProperties.kotlinBootstrapVersion!!, cacheRedirectorEnabled))

    repositories {
        bootstrapKotlinRepo?.let(::maven)

        if (cacheRedirectorEnabled) {
            maven("https://cache-redirector.jetbrains.com/plugins.gradle.org/m2")
            maven("https://cache-redirector.jetbrains.com/repo.maven.apache.org/maven2")

        } else {
            maven("https://plugins.gradle.org/m2")
            mavenCentral()
        }
    }

    // a workaround for kotlin compiler classpath in kotlin project: sometimes gradle substitutes
    // kotlin-stdlib external dependency with local project :kotlin-stdlib in kotlinCompilerClasspath configuration.
    // see also configureCompilerClasspath@
    val bootstrapCompilerClasspath by configurations.creating

    dependencies {
        bootstrapCompilerClasspath(kotlin("compiler-embeddable", bootstrapKotlinVersion))

        classpath("org.jetbrains.kotlin:kotlin-build-gradle-plugin:0.0.32")
        classpath(kotlin("gradle-plugin", bootstrapKotlinVersion))
        classpath(kotlin("serialization", bootstrapKotlinVersion))
        classpath("org.jetbrains.dokka:dokka-gradle-plugin:0.9.17")
    }
}

plugins {
    base
    idea
    id("jps-compatible")
    id("org.jetbrains.gradle.plugin.idea-ext")
    id("org.gradle.crypto.checksum") version "1.2.0"
    signing
}

pill {
    excludedDirs(
        "out",
        "buildSrc/build",
        "buildSrc/prepare-deps/intellij-sdk/build"
    )
}

val isTeamcityBuild = project.kotlinBuildProperties.isTeamcityBuild

val defaultSnapshotVersion: String by extra
val buildNumber by extra(findProperty("build.number")?.toString() ?: defaultSnapshotVersion)
val kotlinVersion by extra(
        findProperty("deployVersion")?.toString()?.let { deploySnapshotStr ->
            if (deploySnapshotStr != "default.snapshot") deploySnapshotStr else defaultSnapshotVersion
        } ?: buildNumber
)

val kotlinLanguageVersion by extra("1.6")

allprojects {
    group = "org.jetbrains.kotlin"
    version = kotlinVersion
}

extra["kotlin_root"] = rootDir

val jpsBootstrap by configurations.creating

val commonBuildDir = File(rootDir, "build")
val distDir by extra("$rootDir/dist")
val distKotlinHomeDir by extra("$distDir/kotlinc")
val distLibDir = "$distKotlinHomeDir/lib"
val commonLocalDataDir = "$rootDir/local"
val ideaSandboxDir = "$commonLocalDataDir/ideaSandbox"
val artifactsDir = "$distDir/artifacts"
val ideaPluginDir = "$artifactsDir/ideaPlugin/Kotlin"

extra["ktorExcludesForDaemon"] = listOf(
    "org.jetbrains.kotlin" to "kotlin-reflect",
    "org.jetbrains.kotlin" to "kotlin-stdlib",
    "org.jetbrains.kotlin" to "kotlin-stdlib-common",
    "org.jetbrains.kotlin" to "kotlin-stdlib-jdk8",
    "org.jetbrains.kotlin" to "kotlin-stdlib-jdk7",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-jdk8",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-core",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-core-common"
)

// TODO: use "by extra()" syntax where possible
extra["distLibDir"] = project.file(distLibDir)
extra["libsDir"] = project.file(distLibDir)
extra["commonLocalDataDir"] = project.file(commonLocalDataDir)
extra["ideaSandboxDir"] = project.file(ideaSandboxDir)
extra["ideaPluginDir"] = project.file(ideaPluginDir)
extra["isSonatypeRelease"] = false
val kotlinNativeVersionObject = project.kotlinNativeVersionValue()
subprojects {
    extra["kotlinNativeVersion"] = kotlinNativeVersionObject
}

rootProject.apply {
    from(rootProject.file("gradle/versions.gradle.kts"))
    from(rootProject.file("gradle/report.gradle.kts"))
    from(rootProject.file("gradle/javaInstrumentation.gradle.kts"))
    from(rootProject.file("gradle/jps.gradle.kts"))
    from(rootProject.file("gradle/checkArtifacts.gradle.kts"))
    from(rootProject.file("gradle/checkCacheability.gradle.kts"))
    from(rootProject.file("gradle/retryPublishing.gradle.kts"))
    from(rootProject.file("gradle/modularizedTestConfigurations.gradle.kts"))
}

IdeVersionConfigurator.setCurrentIde(project)

extra["versions.protobuf"] = "2.6.1"
extra["versions.javax.inject"] = "1"
extra["versions.jsr305"] = "1.3.9"
extra["versions.jansi"] = "1.16"
extra["versions.jline"] = "3.3.1"
extra["versions.junit"] = "4.12"
extra["versions.javaslang"] = "2.0.6"
extra["versions.ant"] = "1.10.7"
extra["versions.android"] = "2.3.1"
extra["versions.kotlinx-coroutines-core"] = "1.5.0"
extra["versions.kotlinx-coroutines-core-jvm"] = "1.5.0"
extra["versions.kotlinx-coroutines-jdk8"] = "1.5.0"
extra["versions.json"] = "20160807"
extra["versions.native-platform"] = "0.14"
extra["versions.robolectric"] = "4.0"
extra["versions.org.springframework"] = "4.2.0.RELEASE"
extra["versions.jflex"] = "1.7.0"
extra["versions.markdown"] = "0.1.25"
extra["versions.trove4j"] = "1.0.20181211"
extra["versions.completion-ranking-kotlin"] = "0.1.3"
extra["versions.r8"] = "2.2.64"
val immutablesVersion = "0.3.1"
extra["versions.kotlinx-collections-immutable"] = immutablesVersion
extra["versions.kotlinx-collections-immutable-jvm"] = immutablesVersion

// NOTE: please, also change KTOR_NAME in pathUtil.kt and all versions in corresponding jar names in daemon tests.
extra["versions.ktor-network"] = "1.0.1"

if (!project.hasProperty("versions.kotlin-native")) {
    extra["versions.kotlin-native"] = "1.6.20-dev-320"
}

val useJvmFir by extra(project.kotlinBuildProperties.useFir)

val intellijSeparateSdks = project.getBooleanProperty("intellijSeparateSdks") ?: false

extra["intellijSeparateSdks"] = intellijSeparateSdks

extra["IntellijCoreDependencies"] =
    listOf(
        "asm-all-9.0",
        "guava",
        "jdom",
        "jna",
        "log4j",
        "snappy-in-java",
        "streamex",
        "trove4j"
    ).filterNotNull()


extra["compilerModules"] = arrayOf(
    ":compiler:util",
    ":compiler:config",
    ":compiler:config.jvm",
    ":compiler:container",
    ":compiler:resolution.common",
    ":compiler:resolution.common.jvm",
    ":compiler:resolution",
    ":compiler:serialization",
    ":compiler:psi",
    ":compiler:frontend",
    ":compiler:frontend.common",
    ":compiler:frontend.common-psi",
    ":compiler:frontend.java",
    ":compiler:frontend:cfg",
    ":compiler:cli-common",
    ":compiler:ir.tree",
    ":compiler:ir.tree.impl",
    ":compiler:ir.tree.persistent",
    ":compiler:ir.psi2ir",
    ":compiler:ir.backend.common",
    ":compiler:backend.jvm",
    ":compiler:backend.jvm:backend.jvm.entrypoint",
    ":compiler:backend.js",
    ":compiler:backend.wasm",
    ":compiler:ir.serialization.common",
    ":compiler:ir.serialization.js",
    ":compiler:ir.serialization.jvm",
    ":compiler:ir.interpreter",
    ":kotlin-util-io",
    ":kotlin-util-klib",
    ":kotlin-util-klib-metadata",
    ":compiler:backend-common",
    ":compiler:backend",
    ":compiler:plugin-api",
    ":compiler:light-classes",
    ":compiler:javac-wrapper",
    ":compiler:cli",
    ":compiler:cli-js",
    ":compiler:incremental-compilation-impl",
    ":compiler:compiler.version",
    ":js:js.ast",
    ":js:js.sourcemap",
    ":js:js.serializer",
    ":js:js.parser",
    ":js:js.config",
    ":js:js.frontend",
    ":js:js.translator",
    ":js:js.dce",
    ":native:frontend.native",
    ":native:kotlin-native-utils",
    ":compiler",
    ":kotlin-build-common",
    ":core:metadata",
    ":core:metadata.jvm",
    ":core:deserialization.common",
    ":core:deserialization.common.jvm",
    ":core:compiler.common",
    ":core:compiler.common.jvm",
    ":compiler:backend.common.jvm",
    ":core:descriptors",
    ":core:descriptors.jvm",
    ":core:descriptors.runtime",
    ":core:deserialization",
    ":core:util.runtime",
    ":compiler:fir:cones",
    ":compiler:fir:resolve",
    ":compiler:fir:fir-serialization",
    ":compiler:fir:fir-deserialization",
    ":compiler:fir:tree",
    ":compiler:fir:raw-fir:raw-fir.common",
    ":compiler:fir:raw-fir:psi2fir",
    ":compiler:fir:raw-fir:light-tree2fir",
    ":compiler:fir:fir2ir",
    ":compiler:fir:fir2ir:jvm-backend",
    ":compiler:fir:java",
    ":compiler:fir:checkers",
    ":compiler:fir:checkers:checkers.jvm",
    ":compiler:fir:entrypoint",
    ":compiler:fir:analysis-tests",
    ":compiler:fir:analysis-tests:legacy-fir-tests",
    ":wasm:wasm.ir"
)

extra["compilerModulesForJps"] = listOf(
    ":kotlin-build-common",
    ":kotlin-util-io",
    ":kotlin-util-klib",
    ":kotlin-util-klib-metadata",
    ":compiler:cli-common",
    ":kotlin-compiler-runner",
    ":daemon-common",
    ":daemon-common-new",
    ":core:compiler.common",
    ":core:compiler.common.jvm",
    ":core:descriptors",
    ":core:descriptors.jvm",
    ":kotlin-preloader",
    ":compiler:util",
    ":compiler:config",
    ":compiler:config.jvm",
    ":js:js.config",
    ":core:util.runtime",
    ":compiler:compiler.version"
)

extra["compilerArtifactsForIde"] = listOf(
    ":prepare:ide-plugin-dependencies:android-extensions-compiler-plugin-for-ide",
    ":prepare:ide-plugin-dependencies:allopen-compiler-plugin-for-ide",
    ":prepare:ide-plugin-dependencies:incremental-compilation-impl-tests-for-ide",
    ":prepare:ide-plugin-dependencies:js-ir-runtime-for-ide",
    ":prepare:ide-plugin-dependencies:kotlin-build-common-tests-for-ide",
    ":prepare:ide-plugin-dependencies:kotlin-compiler-for-ide",
    ":prepare:ide-plugin-dependencies:kotlin-compiler-cli-for-ide",
    ":prepare:ide-plugin-dependencies:kotlin-gradle-statistics-for-ide",
    ":prepare:ide-plugin-dependencies:kotlinx-serialization-compiler-plugin-for-ide",
    ":prepare:ide-plugin-dependencies:noarg-compiler-plugin-for-ide",
    ":prepare:ide-plugin-dependencies:sam-with-receiver-compiler-plugin-for-ide",
    ":prepare:ide-plugin-dependencies:compiler-components-for-jps",
    ":prepare:ide-plugin-dependencies:parcelize-compiler-plugin-for-ide",
    ":prepare:ide-plugin-dependencies:lombok-compiler-plugin-for-ide",
    ":prepare:ide-plugin-dependencies:kotlin-compiler-tests-for-ide",
    ":prepare:ide-plugin-dependencies:kotlin-compiler-testdata-for-ide",
    ":prepare:ide-plugin-dependencies:kotlin-stdlib-minimal-for-test-for-ide",
    ":prepare:ide-plugin-dependencies:low-level-api-fir-for-ide",
    ":prepare:ide-plugin-dependencies:high-level-api-for-ide",
    ":prepare:ide-plugin-dependencies:high-level-api-fir-for-ide",
    ":prepare:ide-plugin-dependencies:high-level-api-fir-tests-for-ide",
    ":prepare:ide-plugin-dependencies:analysis-api-providers-for-ide",
    ":prepare:ide-plugin-dependencies:symbol-light-classes-for-ide",
    ":kotlin-script-runtime",
    ":kotlin-script-util",
    ":kotlin-scripting-common",
    ":kotlin-scripting-jvm",
    ":kotlin-scripting-compiler",
    ":kotlin-scripting-compiler-impl",
    ":kotlin-android-extensions-runtime",
    ":kotlin-stdlib-common",
    ":kotlin-stdlib",
    ":kotlin-stdlib-jdk7",
    ":kotlin-stdlib-jdk8",
    ":kotlin-reflect",
    ":kotlin-main-kts"
)

// TODO: fix remaining warnings and remove this property.
extra["tasksWithWarnings"] = listOf(
    ":kotlin-gradle-plugin:compileKotlin"
)

val tasksWithWarnings: List<String> by extra

val coreLibProjects = listOfNotNull(
    ":kotlin-stdlib",
    ":kotlin-stdlib-common",
    ":kotlin-stdlib-js",
    ":kotlin-stdlib-jdk7",
    ":kotlin-stdlib-jdk8",
    ":kotlin-test",
    ":kotlin-test:kotlin-test-annotations-common",
    ":kotlin-test:kotlin-test-common",
    ":kotlin-test:kotlin-test-jvm",
    ":kotlin-test:kotlin-test-junit",
    ":kotlin-test:kotlin-test-junit5",
    ":kotlin-test:kotlin-test-testng",
    ":kotlin-test:kotlin-test-js".takeIf { !kotlinBuildProperties.isInJpsBuildIdeaSync },
    ":kotlin-reflect"
)

val projectsWithDisabledFirBootstrap = coreLibProjects + listOf(
    ":kotlin-gradle-plugin",
    ":kotlinx-metadata",
    ":kotlinx-metadata-jvm",
    // For some reason stdlib isn't imported correctly for this module
    // Probably it's related to kotlin-test module usage
    ":kotlin-gradle-statistics",
    // Requires serialization plugin
    ":wasm:wasm.ir"
)

val gradlePluginProjects = listOf(
    ":kotlin-gradle-plugin",
    ":kotlin-gradle-plugin-api",
    ":kotlin-allopen",
    ":kotlin-annotation-processing-gradle",
    ":kotlin-noarg",
    ":kotlin-sam-with-receiver",
    ":kotlin-parcelize-compiler",
    ":kotlin-lombok"
)

apply {
    from("libraries/commonConfiguration.gradle")
    from("libraries/configureGradleTools.gradle")
}

apply {
    if (extra["isDeployStagingRepoGenerationRequired"] as? Boolean == true) {
        logger.info("Applying configuration for sonatype release")
        from("libraries/prepareSonatypeStaging.gradle")
    }
}

fun Task.listConfigurationContents(configName: String) {
    doFirst {
        project.configurations.findByName(configName)?.let {
            println("$configName configuration files:\n${it.allArtifacts.files.files.joinToString("\n  ", "  ")}")
        }
    }
}

val ignoreTestFailures by extra(project.kotlinBuildProperties.ignoreTestFailures)

allprojects {
    val mirrorRepo: String? = findProperty("maven.repository.mirror")?.toString()

    repositories {
        kotlinBuildLocalRepo(project)
        mirrorRepo?.let(::maven)

        internalBootstrapRepo?.let(::maven)
        bootstrapKotlinRepo?.let(::maven)
        maven(protobufRepo)

        maven(intellijRepo)

        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-dependencies")
        maven("https://dl.google.com/dl/android/maven2")
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")

        jcenter()
    }

    if (path.startsWith(":kotlin-ide.")) {
        return@allprojects
    }

    configurations.maybeCreate("embedded").apply {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        }
    }

    configurations.maybeCreate("embeddedElements").apply {
        extendsFrom(configurations["embedded"])
        isCanBeConsumed = true
        isCanBeResolved = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named("embedded-java-runtime"))
        }
    }

    // There are problems with common build dir:
    //  - some tests (in particular js and binary-compatibility-validator depend on the fixed (default) location
    //  - idea seems unable to exclude common buildDir from indexing
    // therefore it is disabled by default
    // buildDir = File(commonBuildDir, project.name)

    project.configureJvmDefaultToolchain()
    plugins.withId("java-base") {
        project.configureShadowJarSubstitutionInCompileClasspath()
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
        options.compilerArgs.add("-Werror")
    }

    val commonCompilerArgs = listOfNotNull(
        "-opt-in=kotlin.RequiresOptIn",
        "-progressive".takeIf { hasProperty("test.progressive.mode") }
    )

    tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
        kotlinOptions {
            languageVersion = kotlinLanguageVersion
            apiVersion = kotlinLanguageVersion
            freeCompilerArgs = commonCompilerArgs
        }
    }

    val jvmCompilerArgs = listOf(
        "-Xjvm-default=compatibility",
        "-Xno-optimized-callable-references",
        "-Xno-kotlin-nothing-value-exception",
        "-Xskip-runtime-version-check",
        "-Xsuppress-deprecated-jvm-target-warning" // Remove as soon as there are no modules for JDK 1.6 & 1.7
    )

    tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile> {
        kotlinOptions {
            freeCompilerArgs = commonCompilerArgs + jvmCompilerArgs

            if (useJvmFir && this@allprojects.path !in projectsWithDisabledFirBootstrap) {
                freeCompilerArgs += "-Xuse-fir"
                freeCompilerArgs += "-Xabi-stability=stable"
            }
        }
    }

    if (!kotlinBuildProperties.disableWerror) {
        tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompile> {
            if (path !in tasksWithWarnings) {
                kotlinOptions {
                    allWarningsAsErrors = true
                }
            }
        }
    }

    tasks.withType(VerificationTask::class.java as Class<Task>) {
        (this as VerificationTask).ignoreFailures = ignoreTestFailures
    }

    tasks.withType<Javadoc> {
        enabled = false
    }

    tasks.withType<Jar> {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    tasks.withType<AbstractArchiveTask> {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<Test> {
        outputs.doNotCacheIf("https://youtrack.jetbrains.com/issue/KTI-112") { true }
    }

    if (isConfigurationCacheDisabled) {
        // Custom input normolization isn't supported by configuration cache at the moment
        // See https://github.com/gradle/gradle/issues/13706
        normalization {
            runtimeClasspath {
                ignore("META-INF/MANIFEST.MF")
                ignore("META-INF/compiler.version")
                ignore("META-INF/plugin.xml")
                ignore("kotlin/KotlinVersionCurrentValue.class")
            }
        }
    }

    tasks {
        register("listArchives") { listConfigurationContents("archives") }

        register("listRuntimeJar") { listConfigurationContents("runtimeJar") }

        register("listDistJar") { listConfigurationContents("distJar") }

        // Aggregate task for build related checks
        register("checkBuild")
    }

    apply(from = "$rootDir/gradle/cacheRedirector.gradle.kts")

    afterEvaluate {
        fun File.toProjectRootRelativePathOrSelf() = (relativeToOrNull(rootDir)?.takeUnless { it.startsWith("..") } ?: this).path

        fun FileCollection.printClassPath(role: String) =
            println("${project.path} $role classpath:\n  ${joinToString("\n  ") { it.toProjectRootRelativePathOrSelf() }}")

        try {
            javaPluginConvention()
        } catch (_: UnknownDomainObjectException) {
            null
        }?.let { javaConvention ->
            tasks {
                register("printCompileClasspath") { doFirst { javaConvention.sourceSets["main"].compileClasspath.printClassPath("compile") } }
                register("printRuntimeClasspath") { doFirst { javaConvention.sourceSets["main"].runtimeClasspath.printClassPath("runtime") } }
                register("printTestCompileClasspath") { doFirst { javaConvention.sourceSets["test"].compileClasspath.printClassPath("test compile") } }
                register("printTestRuntimeClasspath") { doFirst { javaConvention.sourceSets["test"].runtimeClasspath.printClassPath("test runtime") } }
            }
        }

        run configureCompilerClasspath@{
            val bootstrapCompilerClasspath by rootProject.buildscript.configurations
            configurations.findByName("kotlinCompilerClasspath")?.let {
                dependencies.add(it.name, files(bootstrapCompilerClasspath))
            }

            configurations.findByName("kotlinCompilerPluginClasspath")
                ?.exclude("org.jetbrains.kotlin", "kotlin-scripting-compiler-embeddable")
        }

        apply(from = "$rootDir/gradle/testRetry.gradle.kts")
    }
}

gradle.taskGraph.whenReady {
    fun Boolean.toOnOff(): String = if (this) "on" else "off"
    val profile = if (isTeamcityBuild) "CI" else "Local"

    val proguardMessage = "proguard is ${kotlinBuildProperties.proguard.toOnOff()}"
    val jarCompressionMessage = "jar compression is ${kotlinBuildProperties.jarCompression.toOnOff()}"

    logger.warn(
        "$profile build profile is active ($proguardMessage, $jarCompressionMessage). " +
                "Use -Pteamcity=<true|false> to reproduce CI/local build"
    )

    allTasks.filterIsInstance<org.gradle.jvm.tasks.Jar>().forEach { task ->
        task.entryCompression = if (kotlinBuildProperties.jarCompression)
            ZipEntryCompression.DEFLATED
        else
            ZipEntryCompression.STORED
    }
}

val dist = tasks.register("dist") {
    dependsOn(":kotlin-compiler:dist")
}

val syncMutedTests = tasks.register("syncMutedTests") {
    dependsOn(":compiler:tests-mutes:tc-integration:run")
}

val copyCompilerToIdeaPlugin by task<Copy> {
    dependsOn(dist)
    into(ideaPluginDir)
    from(distDir) { include("kotlinc/**") }
}

val ideaPlugin by task<Task> {
    dependsOn(copyCompilerToIdeaPlugin)
    dependsOn(":prepare:idea-plugin:ideaPlugin")
}

tasks {
    named<Delete>("clean") {
        delete += setOf("$buildDir/repo", distDir)
    }

    register<Delete>("cleanupArtifacts") {
        delete = setOf(artifactsDir)
    }

    listOf("clean", "assemble", "install").forEach { taskName ->
        register("coreLibs${taskName.capitalize()}") {
            for (projectName in coreLibProjects) {
                if (projectName.startsWith(":kotlin-test:") && taskName == "install") continue
                dependsOn("$projectName:$taskName")
            }
        }
    }

    register("coreLibsTest") {
        (coreLibProjects + listOf(
            ":kotlin-stdlib:samples",
            ":kotlin-stdlib-js-ir",
            ":kotlin-test:kotlin-test-js-ir".takeIf { !kotlinBuildProperties.isInJpsBuildIdeaSync },
            ":kotlin-test:kotlin-test-js:kotlin-test-js-it".takeIf { !kotlinBuildProperties.isInJpsBuildIdeaSync },
            ":kotlinx-metadata-jvm",
            ":tools:binary-compatibility-validator"
        )).forEach {
            dependsOn("$it:check")
        }
    }

    register("gradlePluginTest") {
        gradlePluginProjects.forEach {
            dependsOn("$it:check")
        }
    }

    register("gradlePluginIntegrationTest") {
        dependsOn(":kotlin-gradle-plugin-integration-tests:check")
    }

    register("jvmCompilerTest") {
        dependsOn("dist")
        dependsOn(
            ":compiler:test",
            ":compiler:tests-common-new:test",
            ":compiler:container:test",
            ":compiler:tests-java8:test",
            ":compiler:tests-spec:test",
            ":compiler:tests-against-klib:test"
        )
        dependsOn(":plugins:jvm-abi-gen:test")
    }

    register("jvmCompilerIntegrationTest") {
        dependsOn(
            ":kotlin-compiler-embeddable:test",
            ":kotlin-compiler-client-embeddable:test"
        )
    }

    register("jsCompilerTest") {
        dependsOn(":js:js.tests:test")
        dependsOn(":js:js.tests:runMocha")
    }

    register("wasmCompilerTest") {
        dependsOn(":js:js.tests:wasmTest")
        // Windows WABT release requires Visual C++ Redistributable
        if (!kotlinBuildProperties.isTeamcityBuild || !org.gradle.internal.os.OperatingSystem.current().isWindows) {
            dependsOn(":wasm:wasm.ir:test")
        }
    }

    register("nativeCompilerTest") {
        dependsOn(":native:kotlin-native-utils:test")
    }

    register("firCompilerTest") {
        dependsOn(":compiler:fir:raw-fir:psi2fir:test")
        dependsOn(":compiler:fir:raw-fir:light-tree2fir:test")
        dependsOn(":compiler:fir:analysis-tests:test")
        dependsOn(":compiler:fir:analysis-tests:legacy-fir-tests:test")
        dependsOn(":compiler:fir:fir2ir:test")
    }

    register("firAllTest") {
        dependsOn(
            ":dist",
            ":compiler:fir:raw-fir:psi2fir:test",
            ":compiler:fir:raw-fir:light-tree2fir:test",
            ":compiler:fir:analysis-tests:test",
            ":compiler:fir:analysis-tests:legacy-fir-tests:test",
            ":compiler:fir:fir2ir:test",
            ":plugins:fir:fir-plugin-prototype:test"
        )
    }

    register("compilerFrontendVisualizerTest") {
        dependsOn("compiler:visualizer:test")
    }

    register("scriptingJvmTest") {
        dependsOn("dist")
        dependsOn(":kotlin-script-util:test")
        dependsOn(":kotlin-scripting-compiler:test")
        dependsOn(":kotlin-scripting-compiler:testWithIr")
        dependsOn(":kotlin-scripting-common:test")
        dependsOn(":kotlin-scripting-jvm:test")
        dependsOn(":kotlin-scripting-jvm-host-test:test")
        dependsOn(":kotlin-scripting-jvm-host-test:testWithIr")
        dependsOn(":kotlin-scripting-dependencies:test")
        dependsOn(":kotlin-scripting-dependencies-maven:test")
        dependsOn(":kotlin-scripting-jsr223-test:test")
        // see comments on the task in kotlin-scripting-jvm-host-test
//        dependsOn(":kotlin-scripting-jvm-host-test:embeddableTest")
        dependsOn(":kotlin-scripting-jsr223-test:embeddableTest")
        dependsOn(":kotlin-main-kts-test:test")
        dependsOn(":kotlin-main-kts-test:testWithIr")
        dependsOn(":kotlin-scripting-ide-services-test:test")
        dependsOn(":kotlin-scripting-ide-services-test:embeddableTest")
    }

    register("scriptingTest") {
        dependsOn("scriptingJvmTest")
        dependsOn(":kotlin-scripting-js-test:test")
    }

    register("compilerTest") {
        dependsOn("jvmCompilerTest")
        dependsOn("jsCompilerTest")
        dependsOn("miscCompilerTest")
    }

    register("miscCompilerTest") {
        dependsOn("nativeCompilerTest")
        dependsOn("firCompilerTest")

        dependsOn(":kotlin-daemon-tests:test")
        dependsOn("scriptingTest")
        dependsOn(":kotlin-build-common:test")
        dependsOn(":compiler:incremental-compilation-impl:test")
        dependsOn(":compiler:incremental-compilation-impl:testJvmICWithJdk11")
        dependsOn(":core:descriptors.runtime:test")

        dependsOn("jvmCompilerIntegrationTest")

        dependsOn(":plugins:parcelize:parcelize-compiler:test")
        dependsOn(":kotlinx-serialization-compiler-plugin:test")


        dependsOn(":kotlin-util-io:test")
        dependsOn(":kotlin-util-klib:test")
    }

    register("toolsTest") {
        dependsOn(":tools:kotlinp:test")
        dependsOn(":native:kotlin-klib-commonizer:test")
        dependsOn(":native:kotlin-klib-commonizer-api:test")
    }

    register("examplesTest") {
        dependsOn("dist")
        (project(":examples").subprojects + project(":kotlin-gradle-subplugin-example")).forEach { p ->
            dependsOn("${p.path}:check")
        }
    }

    register("distTest") {
        dependsOn("compilerTest")
        dependsOn("frontendApiTests")
        dependsOn("toolsTest")
        dependsOn("gradlePluginTest")
        dependsOn("examplesTest")
    }

    register("specTest") {
        dependsOn("dist")
        dependsOn(":compiler:tests-spec:test")
    }

    register("androidCodegenTest") {
        dependsOn(":compiler:android-tests:test")
    }

    register("jps-tests") {
        dependsOn("dist")
        dependsOn(":jps-plugin:test")
    }

    register("idea-plugin-additional-tests") {
        dependsOn("dist")
        dependsOn(
            ":idea:idea-maven:test",
            ":nj2k:test",
            ":idea:jvm-debugger:jvm-debugger-core:test",
            ":idea:jvm-debugger:jvm-debugger-evaluation:test",
            ":idea:jvm-debugger:jvm-debugger-sequence:test",
            ":idea:jvm-debugger:eval4j:test",
            ":idea:scripting-support:test"
        )
    }

    if (Ide.IJ()) {
        register("idea-new-project-wizard-tests") {
            dependsOn(
                ":libraries:tools:new-project-wizard:test",
                ":libraries:tools:new-project-wizard:new-project-wizard-cli:test",
                ":idea:idea-new-project-wizard:test"
            )
        }
    }

    register("idea-plugin-performance-tests") {
        dependsOn(
            "dist",
            ":idea:performanceTests:performanceTest",
            ":idea:performanceTests:aggregateResults"
        )
    }

    register("idea-fir-plugin-performance-tests") {
        dependsOn("dist")
        dependsOn(
            ":idea:idea-fir-performance-tests:ideaFirPerformanceTest"
        )
    }

    register("idea-fir-plugin-tests") {
        dependsOn("dist")
        dependsOn(
            ":idea:idea-fir:test",
            ":idea:idea-frontend-fir:fir-low-level-api-ide-impl:test",
            ":plugins:uast-kotlin-fir:test",
            ":idea:idea-fir-fe10-binding:test"
        )
    }

    register("frontendApiTests") {
        dependsOn("dist")
        dependsOn(
            ":idea-frontend-api:test",
            ":idea-frontend-fir:test",
            ":idea-frontend-fir:idea-fir-low-level-api:test"
        )
    }



    register("android-ide-tests") {
        dependsOn("dist")
        dependsOn(
            ":plugins:android-extensions-ide:test",
            ":idea:idea-android:test",
            ":kotlin-annotation-processing:test",
            ":plugins:parcelize:parcelize-ide:test"
        )
    }

    register("ideaPluginTest") {
        dependsOn(
            "mainIdeTests",
            "gradleIdeTest",
            "kaptIdeTest",
            "miscIdeTests"
        )
    }

    register("mainIdeTests") {
        dependsOn(":idea:test")
    }

    register("miscIdeTests") {
        dependsOn(
            ":kotlin-allopen-compiler-plugin:test",
            ":kotlin-noarg-compiler-plugin:test",
            ":kotlin-sam-with-receiver-compiler-plugin:test",
            ":plugins:uast-kotlin:test",
            ":kotlin-annotation-processing-gradle:test",
            ":kotlinx-serialization-ide-plugin:test",
            ":idea:jvm-debugger:jvm-debugger-test:test",
            "idea-plugin-additional-tests",
            "jps-tests",
            ":generators:test"
        )
        if (Ide.IJ()) {
            dependsOn(
                ":libraries:tools:new-project-wizard:test",
                ":libraries:tools:new-project-wizard:new-project-wizard-cli:test"
            )
        }
    }

    register("kaptIdeTest") {
        dependsOn(":kotlin-annotation-processing:test")
        dependsOn(":kotlin-annotation-processing-base:test")
        dependsOn(":kotlin-annotation-processing-cli:test")
    }

    register("gradleIdeTest") {
        dependsOn(
            ":idea:idea-gradle:test",
            ":idea:idea-gradle-native:test"
        )
    }

    register("test") {
        doLast {
            throw GradleException("Don't use directly, use aggregate tasks *-check instead")
        }
    }

    named("check") {
        dependsOn("test")
    }

    named("checkBuild") {
        if (kotlinBuildProperties.isTeamcityBuild) {
            doFirst {
                println("##teamcity[setParameter name='bootstrap.kotlin.version' value='$bootstrapKotlinVersion']")
            }
        }
    }

    register("publishGradlePluginArtifacts") {
        idePluginDependency {
            dependsOnKotlinGradlePluginPublish()
        }
    }

    register("publishIdeArtifacts") {
        idePluginDependency {
            dependsOn((rootProject.extra["compilerArtifactsForIde"] as List<String>).map { "$it:publish" })
        }
    }

    register("installIdeArtifacts") {
        idePluginDependency {
            dependsOn((rootProject.extra["compilerArtifactsForIde"] as List<String>).map { "$it:install" })
        }
    }
}

fun CopySpec.setExecutablePermissions() {
    filesMatching("**/bin/*") { mode = 0b111101101 }
    filesMatching("**/bin/*.bat") { mode = 0b110100100 }
}

val zipCompiler by task<Zip> {
    dependsOn(dist)
    destinationDirectory.set(file(distDir))
    archiveFileName.set("kotlin-compiler-$kotlinVersion.zip")

    from(distKotlinHomeDir)
    into("kotlinc")
    setExecutablePermissions()

    doLast {
        logger.lifecycle("Compiler artifacts packed to ${archiveFile.get().asFile.absolutePath}")
    }
}

val zipStdlibTests by task<Zip> {
    destinationDirectory.set(file(distDir))
    archiveFileName.set("kotlin-stdlib-tests.zip")
    from("libraries/stdlib/common/test") { into("common") }
    from("libraries/stdlib/test") { into("test") }
    from("libraries/kotlin.test/common/src/test/kotlin") { into("kotlin-test") }
    doLast {
        logger.lifecycle("Stdlib tests are packed to ${archiveFile.get()}")
    }
}

val zipTestData by task<Zip> {
    dependsOn(zipStdlibTests)
    destinationDirectory.set(file(distDir))
    archiveFileName.set("kotlin-test-data.zip")
    isZip64 = true
    from("compiler/testData") { into("compiler") }
    from("idea/testData") { into("ide") }
    from("idea/idea-completion/testData") { into("ide/completion") }
    from("compiler/tests-common/tests/org/jetbrains/kotlin/coroutineTestUtil.kt") { into("compiler") }
    doLast {
        logger.lifecycle("Test data packed to ${archiveFile.get()}")
    }
}

val zipPlugin by task<Zip> {
    val src = when (project.findProperty("pluginArtifactDir") as String?) {
        "Kotlin" -> ideaPluginDir
        null -> ideaPluginDir
        else -> error("Unsupported plugin artifact dir")
    }
    val destPath = project.findProperty("pluginZipPath") as String?
    val dest = File(destPath ?: "$buildDir/kotlin-plugin.zip")
    destinationDirectory.set(dest.parentFile)
    archiveFileName.set(dest.name)

    from(src)
    into("Kotlin")
    setExecutablePermissions()

    doLast {
        logger.lifecycle("Plugin artifacts packed to ${archiveFile.get()}")
    }
}

fun Project.secureZipTask(zipTask: TaskProvider<Zip>): RegisteringDomainObjectDelegateProviderWithAction<out TaskContainer, Task> {
    val checkSumTask = tasks.register("${zipTask.name}Checksum", Checksum::class) {
        dependsOn(zipTask)
        val compilerFile = zipTask.get().outputs.files.singleFile
        files = files(compilerFile)
        outputDir = compilerFile.parentFile
        algorithm = Checksum.Algorithm.SHA256
    }

    val signTask = tasks.register("${zipTask.name}Sign", Sign::class) {
        description = "Signs the archive produced by the '" + zipTask.name + "' task."
        sign(zipTask.get())
    }

    return tasks.registering {
        dependsOn(checkSumTask)
        dependsOn(signTask)
    }
}

signing {
    useGpgCmd()
}

val zipCompilerWithSignature by secureZipTask(zipCompiler)
val zipPluginWithSignature by secureZipTask(zipPlugin)

configure<IdeaModel> {
    module {
        excludeDirs = files(
            project.buildDir,
            commonLocalDataDir,
            ".gradle",
            "dependencies",
            "dist",
            "tmp"
        ).toSet()
    }
}

tasks.register("findShadowJarsInClasspath") {
    doLast {
        fun Collection<File>.printSorted(indent: String = "    ") {
            sortedBy { it.path }.forEach { println(indent + it.relativeTo(rootProject.projectDir)) }
        }

        val mainJars = hashSetOf<File>()
        val shadowJars = hashSetOf<File>()
        for (project in rootProject.allprojects) {
            project.withJavaPlugin {
                project.sourceSets.forEach { sourceSet ->
                    val jarTask = project.tasks.findByPath(sourceSet.jarTaskName) as? Jar
                    jarTask?.outputFile?.let { mainJars.add(it) }
                }
            }
            for (task in project.tasks) {
                when (task) {
                    is ShadowJar -> {
                        shadowJars.add(fileFrom(task.outputFile))
                    }
                    is ProGuardTask -> {
                        shadowJars.addAll(task.outputs.files.toList())
                    }
                }
            }
        }

        shadowJars.removeAll(mainJars)
        println("Shadow jars that might break incremental compilation:")
        shadowJars.printSorted()

        fun Project.checkConfig(configName: String) {
            val config = configurations.findByName(configName) ?: return
            val shadowJarsInConfig = config.resolvedConfiguration.files.filter { it in shadowJars }
            if (shadowJarsInConfig.isNotEmpty()) {
                println()
                println("Project $project contains shadow jars in configuration '$configName':")
                shadowJarsInConfig.printSorted()
            }
        }

        for (project in rootProject.allprojects) {
            project.sourceSetsOrNull?.forEach { sourceSet ->
                project.checkConfig(sourceSet.compileClasspathConfigurationName)
            }
        }
    }
}

val Jar.outputFile: File
    get() = archiveFile.get().asFile

val Project.sourceSetsOrNull: SourceSetContainer?
    get() = convention.findPlugin(JavaPluginConvention::class.java)?.sourceSets

val disableVerificationTasks = providers.systemProperty("disable.verification.tasks")
    .forUseAtConfigurationTime().orNull?.toBoolean() ?: false
if (disableVerificationTasks) {
    gradle.taskGraph.whenReady {
        allTasks.forEach {
            if (it is VerificationTask) {
                logger.info("DISABLED: '$it'")
                it.enabled = false
            }
        }
    }
}

plugins.withType(org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin::class) {
    extensions.configure(org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension::class.java) {
        nodeVersion = "16.2.0"
    }
}

afterEvaluate {
    val cacheRedirectorEnabled = findProperty("cacheRedirectorEnabled")?.toString()?.toBoolean() == true
    if (cacheRedirectorEnabled) {
        rootProject.plugins.withType(org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin::class.java) {
            rootProject.the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().downloadBaseUrl =
                "https://cache-redirector.jetbrains.com/github.com/yarnpkg/yarn/releases/download"
        }
    }
}
