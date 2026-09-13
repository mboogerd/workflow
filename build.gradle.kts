plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
}

group = "io.workflow"
version = "0.1.0"

repositories { mavenCentral() }

dependencies {
    implementation("com.charleskorn.kaml:kaml:0.77.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
application { mainClass.set("io.workflow.ApplicationKt") }

