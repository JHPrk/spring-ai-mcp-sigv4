description = "Spring Boot auto-configuration for Spring AI MCP AWS SigV4 authentication"

dependencies {
	implementation(project(":spring-ai-mcp-sigv4"))

	compileOnly(libs.spring.boot.autoconfigure)
	compileOnly(libs.spring.ai.autoconfigure.mcp.client.common)

	annotationProcessor(libs.spring.boot.autoconfigure.processor)
	annotationProcessor(libs.spring.boot.configuration.processor)

	testImplementation(libs.spring.boot.autoconfigure)
	testImplementation(libs.spring.ai.autoconfigure.mcp.client.common)
	testImplementation(libs.spring.ai.autoconfigure.mcp.client.httpclient)
}

