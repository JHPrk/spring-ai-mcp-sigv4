description = "AWS Signature Version 4 request signing for Spring AI MCP HTTP clients"

dependencies {
	api(libs.spring.ai.mcp)
	api(libs.aws.auth)
	api(libs.aws.regions)

	implementation(libs.aws.http.auth.aws)
	implementation(libs.reactor.core)

	testImplementation(libs.reactor.test)
}

