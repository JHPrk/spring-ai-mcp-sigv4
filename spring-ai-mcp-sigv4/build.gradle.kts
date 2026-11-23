description = "AWS Signature Version 4 request signing for Spring AI MCP HTTP clients"

dependencies {
	api(libs.mcp.core)
	api(libs.spring.core)
	api(libs.aws.auth)
	api(libs.aws.regions)

	implementation(libs.aws.http.auth.aws)
	implementation(libs.reactor.core)

	testImplementation(libs.reactor.test)
	testImplementation(libs.mcp.json.jackson3)
}

sourceSets.named("test") {
	java.srcDir(rootProject.file("verification/core-consumer/src/test/java"))
}

val testSourceSet = extensions.getByType<JavaPluginExtension>().sourceSets.named("test")

tasks.register<Test>("integrationTest") {
	description = "Runs opt-in integration tests"
	systemProperty("logback.configurationFile", rootProject.file("verification/logback-live.xml").absolutePath)
	systemProperty("jdk.httpclient.HttpClient.log", "")
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	outputs.upToDateWhen { false }
	outputs.doNotCacheIf("Live AWS verification must run against the current environment") { true }
	testClassesDirs = testSourceSet.get().output.classesDirs
	classpath = testSourceSet.get().runtimeClasspath
	shouldRunAfter(tasks.named("test"))
	useJUnitPlatform {
		includeTags("integration")
	}
}

// The agent is a test JVM input, never a published runtime dependency.
val otelAgent = configurations.create("otelAgent") {
	isCanBeConsumed = false
	isTransitive = false
}

dependencies {
	add(otelAgent.name, "io.opentelemetry.javaagent:opentelemetry-javaagent:2.34.0")
	testImplementation("io.opentelemetry:opentelemetry-api")
}

tasks.named<Test>("test") {
	useJUnitPlatform { excludeTags("otel-agent") }
}

val agentOverride = providers.gradleProperty("otelAgentJar")
val agentFiles = if (agentOverride.isPresent) files(agentOverride.get()) else otelAgent

val otelAgentTest = tasks.register<Test>("otelAgentTest") {
	description = "Verifies local SigV4 requests with real JDK HttpClient javaagent instrumentation"
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	testClassesDirs = testSourceSet.get().output.classesDirs
	classpath = testSourceSet.get().runtimeClasspath
	useJUnitPlatform { includeTags("otel-agent") }
	useHttpInstrumentation(agentFiles)
}

tasks.named("check") { dependsOn(otelAgentTest) }

// Live instrumentation is explicit and never changes the default AWS-free check lifecycle.
tasks.named<Test>("integrationTest") {
	if (providers.gradleProperty("agentCoreItTracing").orElse("false").get().toBoolean()) {
		environment("MCP_IAM_IT_TRACING", "true")
		useHttpInstrumentation(agentFiles)
	}
}

fun Test.useHttpInstrumentation(agentFiles: org.gradle.api.file.FileCollection) {
	inputs.files(agentFiles).withPropertyName("otelAgent")
	systemProperty("otel.javaagent.enabled", "true")
	systemProperty("otel.sdk.disabled", "false")
	systemProperty("otel.traces.exporter", "none")
	systemProperty("otel.metrics.exporter", "none")
	systemProperty("otel.logs.exporter", "none")
	systemProperty("otel.traces.sampler", "always_on")
	systemProperty("otel.propagators", "tracecontext,baggage")
	systemProperty("otel.instrumentation.java-http-client.enabled", "true")
	systemProperty("otel.instrumentation.opentelemetry-api.enabled", "true")
	systemProperty("otel.javaagent.logging", "none")
	doFirst { jvmArgs("-javaagent:${agentFiles.singleFile.absolutePath}") }
}
