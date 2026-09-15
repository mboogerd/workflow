plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    application
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "dev.socaity.workflow"
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
tasks.test { useJUnitPlatform() }
application { mainClass.set("io.workflow.ApplicationKt") }

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(group.toString(), "workflow", version.toString())

    pom {
        name.set("Workflow")
        description.set("A backend-independent workflow language and Kotlin/JVM runtime.")
        inceptionYear.set("2026")
        url.set("https://github.com/mboogerd/workflow")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("mboogerd")
                name.set("Merlijn Boogerd")
                url.set("https://github.com/mboogerd")
            }
        }

        scm {
            url.set("https://github.com/mboogerd/workflow")
            connection.set("scm:git:https://github.com/mboogerd/workflow.git")
            developerConnection.set("scm:git:ssh://git@github.com/mboogerd/workflow.git")
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
