pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		mavenCentral()
	}
}

rootProject.name = "spring-ai-mcp-sigv4"

include(
	"spring-ai-mcp-sigv4",
	"spring-ai-mcp-sigv4-spring-boot-autoconfigure",
	"spring-ai-mcp-sigv4-spring-boot-starter",
	"samples:agentcore-client"
)
