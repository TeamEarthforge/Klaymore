pluginManagement {
  repositories {
    maven {
      // RetroFuturaGradle
      name = "GTNH Maven"
      url = uri("https://nexus.gtnewhorizons.com/repository/public/")
      mavenContent {
        includeGroup("com.gtnewhorizons")
        includeGroupByRegex("com\\.gtnewhorizons\\..+")
      }
    }
    gradlePluginPortal()
    mavenCentral()
    mavenLocal()
  }
}

plugins {
  id("com.gtnewhorizons.gtnhsettingsconvention") version ("2.0.20")
  id("com.gradleup.shadow") version ("9.0.0-beta4") apply (false)
  kotlin("jvm") version ("2.2.21") apply (false)
}

include(":script-runtime")

include(":klaymore-compiler")
