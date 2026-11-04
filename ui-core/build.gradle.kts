plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.jichuo1.meguviz.ui.core"
    compileSdk = 37
    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    lint { abortOnError = true; warningsAsErrors = true }
}

kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

dependencies {
    testImplementation(libs.junit)
    testImplementation(project(":ui-testkit"))
}

dependencyLocking { lockAllConfigurations() }

afterEvaluate {
    val unitTests = tasks.named<Test>("testDebugUnitTest")
    val compileProbe = tasks.register<JavaCompile>("compileSchedulerProbe") {
        dependsOn(unitTests)
        source(rootProject.file("scripts/performance/SchedulerProbe.java"))
        classpath = files(unitTests.map { it.classpath })
        destinationDirectory.set(layout.buildDirectory.dir("scheduler-probe/classes"))
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    tasks.register<JavaExec>("schedulerProbe") {
        group = "verification"
        description = "Runs a host-only scheduler latency/allocation probe; not an Android performance gate."
        dependsOn(compileProbe)
        classpath = files(compileProbe.flatMap { it.destinationDirectory }, unitTests.map { it.classpath })
        mainClass.set("SchedulerProbe")
        minHeapSize = "256m"
        maxHeapSize = "256m"
        args(providers.gradleProperty("probeWarmup").getOrElse("2000"),
            providers.gradleProperty("probeIterations").getOrElse("4000"))
    }
}

publishing {
    repositories { maven { name = "Candidate"; url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI() } }
    publications { register<MavenPublication>("release") {
        artifactId = "meguviz-ui-core"
        afterEvaluate { from(components["release"]) }
        pom { name = "MeguvizUI Core"; description = "Independent native View sessions and update contracts." }
    } }
}
