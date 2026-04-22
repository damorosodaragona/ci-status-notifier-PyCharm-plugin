plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.14.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/PyCharm.app")
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.skillab.projector.ci-status-notifier"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "233"
        }
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("233")
    }
}
