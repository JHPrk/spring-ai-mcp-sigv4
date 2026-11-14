description = "Starter for Spring AI MCP clients authenticated with AWS Signature Version 4"

dependencies {
	api(project(":spring-ai-mcp-sigv4"))
	api(project(":spring-ai-mcp-sigv4-spring-boot-autoconfigure"))
	api(libs.spring.ai.starter.mcp.client)
}

sourceSets.named("test") {
	java.srcDir(rootProject.file("verification/consumer/src/test/java"))
}

val testSourceSet = extensions.getByType<JavaPluginExtension>().sourceSets.named("test")
tasks.register<Test>("integrationTest") {
	description = "Runs opt-in live verification of the auto-configured starter integration"
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	systemProperty("jdk.httpclient.HttpClient.log", "")
	outputs.upToDateWhen { false }
	outputs.doNotCacheIf("Live AWS verification must run against the current environment") { true }
	testClassesDirs = testSourceSet.get().output.classesDirs
	classpath = testSourceSet.get().runtimeClasspath
	shouldRunAfter(tasks.named("test"))
	useJUnitPlatform { includeTags("integration") }
}
