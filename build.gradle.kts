plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.14.0"
    id("info.solidsoft.pitest") version "1.19.0"

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
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")


    intellijPlatform {
        val localPyCharm = file("/Applications/PyCharm.app")
        if (localPyCharm.exists() && !providers.environmentVariable("CI").isPresent) {
            local(localPyCharm.absolutePath)
        } else {
            pycharmCommunity(providers.gradleProperty("platformVersion"))
        }
        bundledPlugin("Git4Idea")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.damorosodaragona.jenkinsnotifier"
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


tasks.test {
    useJUnitPlatform()
}

pitest {
    targetClasses.set(listOf(
             "com.damorosodaragona.jenkinsnotifier.CiStatusBuildLogic",
        "com.damorosodaragona.jenkinsnotifier.AuthNotificationCoordinator",
    ))

    val pitTargetTests = providers.gradleProperty("pitTargetTests")
    val pitSafeTestsFile = providers.gradleProperty("pitSafeTestsFile")

    targetTests.set(
        pitTargetTests.map { listOf(it) }
            .orElse(
                pitSafeTestsFile.map { fileName ->
                    file(fileName)
                        .readLines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                }
            )
            .orElse(
                listOf(
                    "com.damorosodaragona.jenkinsnotifier.AuthNotificationCoordinatorFlowTest",
                    "com.damorosodaragona.jenkinsnotifier.AuthNotificationCoordinatorTest",
                    "com.damorosodaragona.jenkinsnotifier.CiStatusBuildLogicTest",
                    "com.damorosodaragona.jenkinsnotifier.CiStatusBuildTransitionLogicTest",
                    "com.damorosodaragona.jenkinsnotifier.JenkinsBuildSummaryTest"
                )
            )
    )


    pitestVersion.set("1.22.1")
    junit5PluginVersion.set("1.2.1")
    threads.set(2)
    outputFormats.set(listOf("HTML"))
    timestampedReports.set(false)
}
