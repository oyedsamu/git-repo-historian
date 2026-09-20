plugins {
    kotlin("jvm") version "2.1.20"
    id("com.google.devtools.ksp") version "2.1.20-2.0.1"
    application
}

repositories {
    mavenCentral()
}

val adkVersion = "1.1.0"

// The ADK dev server pulls in Ktor and Netty, which the CLI never touches. Keeping it in its
// own source set keeps roughly 40MB of server dependencies out of the published distribution.
val webui: SourceSet by sourceSets.creating

val webuiImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}
configurations["webuiRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    implementation("com.google.adk:google-adk-kotlin-core:$adkVersion")
    ksp("com.google.adk:google-adk-kotlin-processor:$adkVersion")

    // Without a binding, SLF4J prints "No SLF4J providers were found" on every start.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    webuiImplementation(sourceSets.main.get().output)
    webuiImplementation("com.google.adk:google-adk-kotlin-webserver:$adkVersion")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "git-repo-historian"
    mainClass.set("dev.sam.historian.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

/** The ADK dev UI on http://localhost:8080. Not part of the published distribution. */
tasks.register<JavaExec>("runWeb") {
    group = "application"
    description = "Runs the ADK dev server UI (development only)."
    mainClass.set("dev.sam.historian.WebMainKt")
    classpath = webui.runtimeClasspath
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}
