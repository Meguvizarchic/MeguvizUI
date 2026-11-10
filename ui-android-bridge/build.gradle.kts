plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.jichuo1.meguviz.ui.android"
    compileSdk = 37
    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "io.github.jichuo1.meguviz.ui.android.BridgeInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; warningsAsErrors = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    api(project(":ui-core"))
    api(libs.meguviz.android.runtime)
    api(libs.meguviz.android.window)
    testImplementation(libs.junit)
}
dependencyLocking { lockAllConfigurations() }

val runtimeCandidate = providers.gradleProperty("runtimeCandidateClasses")
if (runtimeCandidate.isPresent) afterEvaluate {
    val regularTests = tasks.named<Test>("testDebugUnitTest")
    val candidate = rootProject.file(runtimeCandidate.get())
    val candidateTests = tasks.register<Test>("testRuntimeCandidate") {
        group = "verification"
        description = "Runs bridge contracts with a local runtime candidate first on the JVM classpath."
        dependsOn(regularTests)
        inputs.file(candidate)
        testClassesDirs = regularTests.get().testClassesDirs
        classpath = files(candidate, regularTests.get().classpath)
        systemProperty("meguviz.runtime.candidate", candidate.absolutePath)
        filter { includeTestsMatching("io.github.jichuo1.meguviz.ui.android.AndroidBridgeContractTest") }
        doFirst { check(candidate.isFile && candidate.extension == "jar") { "runtimeCandidateClasses must name an extracted classes.jar" } }
    }
    val drawableCandidate = providers.gradleProperty("drawableCandidateJar")
    if (drawableCandidate.isPresent) {
        val drawable = rootProject.file(drawableCandidate.get())
        val compileJointProbe = tasks.register<JavaCompile>("compileJointPipelineProbe") {
            dependsOn(candidateTests)
            source(rootProject.file("scripts/performance/JointPipelineProbe.java"))
            classpath = files(candidate, drawable, regularTests.map { it.classpath })
            destinationDirectory.set(layout.buildDirectory.dir("joint-probe/classes"))
            sourceCompatibility = "17"
            targetCompatibility = "17"
            doFirst { check(drawable.isFile && drawable.extension == "jar") { "drawableCandidateJar must name the core candidate jar" } }
        }
        tasks.register<JavaExec>("jointPipelineProbe") {
            group = "verification"
            description = "Opt-in host contract for Drawable preparation, UI coalescing and one Android frame source."
            dependsOn(compileJointProbe)
            classpath = files(compileJointProbe.flatMap { it.destinationDirectory }, candidate, drawable,
                regularTests.map { it.classpath })
            mainClass.set("JointPipelineProbe")
            systemProperty("meguviz.runtime.candidate.uri", candidate.toURI().toURL().toExternalForm())
            systemProperty("meguviz.drawable.candidate.uri", drawable.toURI().toURL().toExternalForm())
        }
    }
}

publishing {
    repositories { maven { name = "Candidate"; url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI() } }
    publications { register<MavenPublication>("release") {
        artifactId = "meguviz-ui-android-bridge"
        afterEvaluate { from(components["release"]) }
        pom { name = "MeguvizUI Android bridge"; description = "Optional single-frame and resource-authority adapter for real MeguvizAndroid APIs." }
    } }
}
