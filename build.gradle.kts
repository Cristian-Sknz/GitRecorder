plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("kapt") version "2.4.20"
    application
    id("org.graalvm.buildtools.native") version "1.1.14"
}

repositories { mavenCentral() }

dependencies {
    implementation("info.picocli:picocli:4.7.7")
    kapt("info.picocli:picocli-codegen:4.7.7")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

kotlin { jvmToolchain(21) }

kapt { arguments { arg("project", "dev.gitrecorder/gitrecorder") } }

application { mainClass.set("dev.gitrecorder.MainKt") }

tasks.test { useJUnitPlatform() }

tasks.register<Jar>("fatJar") {
    archiveFileName.set("gitrecorder.jar")
    destinationDirectory.set(layout.buildDirectory)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = "dev.gitrecorder.MainKt"
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().filter { it.extension == "jar" }.map(::zipTree) })
}

graalvmNative {
    metadataRepository { enabled.set(false) }
    binaries {
        named("main") {
            imageName.set("gitrecorder")
            mainClass.set("dev.gitrecorder.MainKt")
            buildArgs.add("--no-fallback")
        }
    }
}
