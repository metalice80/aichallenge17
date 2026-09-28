pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "day19-mcp-pipeline"
include("mcp-pipeline-server", "agent-app")
