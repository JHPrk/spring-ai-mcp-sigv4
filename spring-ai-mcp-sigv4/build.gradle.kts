description = "AWS Signature Version 4 request signing for Spring AI MCP HTTP clients"

dependencies {
	api(libs.spring.ai.mcp)
	api(libs.aws.auth)
	api(libs.aws.regions)

	implementation(libs.aws.http.auth.aws)
	implementation(libs.reactor.core)

	testImplementation(libs.reactor.test)
}

val testSourceSet = extensions.getByType<JavaPluginExtension>().sourceSets.named("test")

tasks.register<Test>("integrationTest") {
	description = "Runs opt-in integration tests"
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	testClassesDirs = testSourceSet.get().output.classesDirs
	classpath = testSourceSet.get().runtimeClasspath
	shouldRunAfter(tasks.named("test"))
	useJUnitPlatform {
		includeTags("integration")
	}
}
