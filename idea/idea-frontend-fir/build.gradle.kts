
plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(project(":compiler:psi"))
    compile(project(":compiler:fir:fir2ir"))
    compile(project(":compiler:ir.tree"))
    compile(project(":compiler:fir:resolve"))
    compile(project(":compiler:fir:checkers"))
    compile(project(":compiler:fir:checkers:checkers.jvm"))
    compile(project(":compiler:fir:java"))
    compile(project(":idea-frontend-fir:idea-fir-low-level-api"))
    compile(project(":idea-frontend-api"))
    compile(project(":compiler:light-classes"))
    compile(intellijCoreDep())
    implementation(project(":analysis:analysis-api-providers"))

    testCompile(projectTests(":idea-frontend-fir:idea-fir-low-level-api"))
    testCompile(projectTests(":compiler:tests-common"))
    testCompile(projectTests(":compiler:test-infrastructure-utils"))
    testCompile(projectTests(":compiler:test-infrastructure"))
    testCompile(projectTests(":compiler:tests-common-new"))
    testCompile(projectTests(":compiler:fir:analysis-tests:legacy-fir-tests"))
    testCompile(project(":kotlin-test:kotlin-test-junit"))
    testCompile(toolsJar())
    testApiJUnit5()
    testRuntime(project(":analysis:symbol-light-classes"))

    testRuntimeOnly(intellijDep()) {
        includeJars(
            "jps-model",
            "extensions",
            "util",
            "platform-api",
            "platform-impl",
            "idea",
            "guava",
            "trove4j",
            "asm-all",
            "log4j",
            "jdom",
            "streamex",
            "bootstrap",
            "jna",
            rootProject = rootProject
        )
    }
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

projectTest(jUnit5Enabled = true) {
    dependsOn(":dist")
    workingDir = rootDir
    useJUnitPlatform()
}

testsJar()

allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
        kotlinOptions {
            freeCompilerArgs += "-opt-in=org.jetbrains.kotlin.fir.symbols.SymbolInternals"
        }
    }
}

val generatorClasspath by configurations.creating

dependencies {
    generatorClasspath(project(":idea-frontend-fir:idea-frontend-fir-generator"))
}

val generateCode by tasks.registering(NoDebugJavaExec::class) {
    val generatorRoot = "$projectDir/idea/idea-frontend-fir/idea-frontend-fir-generator/src/"

    val generatorConfigurationFiles = fileTree(generatorRoot) {
        include("**/*.kt")
    }

    inputs.files(generatorConfigurationFiles)

    workingDir = rootDir
    classpath = generatorClasspath
    main = "org.jetbrains.kotlin.idea.frontend.api.fir.generator.MainKt"
    systemProperties["line.separator"] = "\n"
}

val compileKotlin by tasks

compileKotlin.dependsOn(generateCode)


