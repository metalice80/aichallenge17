plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
}

tasks.bootJar { archiveFileName.set("agent-app.jar") }

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}

tasks.named("processResources") {
    dependsOn(":mcp-weather-server:bootJar", ":mcp-guide-server:bootJar", ":mcp-files-server:bootJar")
}

tasks.withType<Test>().configureEach {
    dependsOn(":mcp-weather-server:bootJar", ":mcp-guide-server:bootJar", ":mcp-files-server:bootJar")
}
