plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.jichuo1.meguviz.ui.recycler"
    compileSdk = 37
    defaultConfig {
        minSdk = 27
        testInstrumentationRunner = "io.github.jichuo1.meguviz.ui.recycler.RecyclerInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; warningsAsErrors = true }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
dependencies {
    api(project(":ui-core"))
    api(libs.recyclerview)
    testImplementation(libs.junit)
}
dependencyLocking { lockAllConfigurations() }

afterEvaluate {
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    val compileProbe = tasks.register<JavaCompile>("compileLatestWorkProbe") {
        dependsOn(unitTests)
        source(rootProject.file("scripts/performance/LatestWorkProbe.java"))
        classpath = files(unitTests.map { it.classpath })
        destinationDirectory.set(layout.buildDirectory.dir("latest-work-probe/classes"))
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    tasks.register<JavaExec>("latestWorkProbe") {
        group = "verification"
        description = "Measures host-only bounded latest-work admission allocation; not an Android performance gate."
        dependsOn(compileProbe)
        classpath = files(compileProbe.flatMap { it.destinationDirectory }, unitTests.map { it.classpath })
        mainClass.set("LatestWorkProbe")
    }
}

publishing {
    repositories { maven { name = "Candidate"; url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI() } }
    publications { register<MavenPublication>("release") {
        artifactId = "meguviz-ui-recycler"
        afterEvaluate { from(components["release"]) }
        pom { name = "MeguvizUI RecyclerView"; description = "Optional bounded RecyclerView list binding." }
    } }
}
