plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
    `maven-publish`
}

group = "io.workflow"
version = providers.gradleProperty("releaseVersion").orElse("1.0.0").get()

repositories { mavenCentral() }

dependencies {
    implementation("com.charleskorn.kaml:kaml:0.77.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

kotlin { jvmToolchain(21) }
java { withSourcesJar() }
tasks.test { useJUnitPlatform() }
application { mainClass.set("io.workflow.ApplicationKt") }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Workflow")
                description.set("A backend-independent workflow language and Kotlin/JVM runtime.")
            }
        }
    }

    val nexusReleaseUrl = providers.environmentVariable("NEXUS_RELEASE_URL")
    if (nexusReleaseUrl.isPresent) {
        repositories {
            maven {
                name = "nexus"
                url = uri(nexusReleaseUrl.get())
                credentials {
                    username = providers.environmentVariable("NEXUS_USERNAME").orNull
                    password = providers.environmentVariable("NEXUS_PASSWORD").orNull
                }
            }
        }
    }
}

tasks.register<JavaExec>("conformance") {
    group = "verification"
    description = "Runs the versioned Workflow v1 conformance corpus."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.workflow.conformance.ConformanceKt")
    args("conformance/v1")
}

tasks.register<Exec>("distributionSmokeTest") {
    group = "verification"
    description = "Validates the generated distribution in a clean directory."
    dependsOn(tasks.named("installDist"))
    commandLine("bash", "scripts/distribution-smoke-test.sh", layout.buildDirectory.get().asFile.absolutePath)
}
