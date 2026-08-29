plugins {
  id("com.gtnewhorizons.gtnhconvention")
  kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {}

afterEvaluate {
  configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    kotlin {
      clearSteps()
      ktfmt("0.52")
    }
    kotlinGradle {
      clearSteps()
      ktfmt("0.52")
    }
  }
}
