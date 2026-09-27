import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
}

allprojects {
    group = "com.example"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs MCP STDIO integration tests."
    dependsOn(":mcp-weather-server:integrationTest")
}
