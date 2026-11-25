import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "dev.aigw.sandbox"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.2")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
            create(IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
}

// `./gradlew runIde263` starts IntelliJ IDEA 2026.3 with the plugin (the default `runIde` uses the 2026.2 build target).
intellijPlatformTesting {
    runIde {
        register("runIde263") {
            type = IntelliJPlatformType.IntellijIdea
            version = "2026.3"
        }
    }
}
