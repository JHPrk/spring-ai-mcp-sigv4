description = "Starter for Spring AI MCP clients authenticated with AWS Signature Version 4"

dependencies {
	api(project(":spring-ai-mcp-sigv4"))
	api(project(":spring-ai-mcp-sigv4-spring-boot-autoconfigure"))
	api(libs.spring.ai.starter.mcp.client)
}
