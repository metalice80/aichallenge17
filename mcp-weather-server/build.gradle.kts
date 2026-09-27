import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework:spring-web")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<BootJar>("bootJar") {
    archiveFileName.set("mcp-weather-server.jar")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val serverBootJar = tasks.named<BootJar>("bootJar")

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs the real MCP server jar over STDIO."
    useJUnitPlatform {
        includeTags("integration")
    }
    dependsOn(serverBootJar)
    shouldRunAfter(tasks.test)
    systemProperty(
        "mcp.server.jar",
        layout.buildDirectory.file("libs/mcp-weather-server.jar").get().asFile.absolutePath,
    )
}
