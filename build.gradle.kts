plugins {
    kotlin("jvm") version "2.1.20"
    id("com.google.devtools.ksp") version "2.1.20-2.0.1"
    application
}

repositories {
    mavenCentral()
}

val adkVersion = "1.1.0"

dependencies {
    implementation("com.google.adk:google-adk-kotlin-core:$adkVersion")
    implementation("com.google.adk:google-adk-kotlin-webserver:$adkVersion")
    ksp("com.google.adk:google-adk-kotlin-processor:$adkVersion")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set(
        project.findProperty("mainClass") as? String ?: "dev.sam.historian.MainKt"
    )
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}
