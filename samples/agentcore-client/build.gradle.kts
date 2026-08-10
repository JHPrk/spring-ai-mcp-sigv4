plugins {
	application
}

description = "Minimal Amazon Bedrock AgentCore Gateway MCP client sample"

dependencies {
	implementation(project(":spring-ai-mcp-sigv4-spring-boot-starter"))
}

application {
	mainClass = "org.springaicommunity.mcp.sigv4.sample.AgentCoreClientApplication"
}

