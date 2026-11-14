import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory
import org.gradle.jvm.toolchain.JavaToolchainService
import org.w3c.dom.Document

val modules = subprojects.filter { it.plugins.hasPlugin("maven-publish") }.map { it.name }
val publicationGroup = providers.gradleProperty("projectGroup").get()
val publicationVersion = providers.gradleProperty("projectVersion").get()
val repository = layout.buildDirectory.dir("verification-repository")
val consumerRoot = layout.buildDirectory.dir("consumer-verification")
val bootVersion = extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("spring-boot").get().requiredVersion
val launcher = project(":spring-ai-mcp-sigv4").extensions.getByType<JavaToolchainService>().launcherFor {
	languageVersion = JavaLanguageVersion.of(providers.gradleProperty("testJavaVersion").orElse("17").get().toInt())
}

fun xml(file: File): Document = DocumentBuilderFactory.newInstance().apply {
	setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
	setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
	setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
}.newDocumentBuilder().parse(file)

fun Document.value(path: String): String = XPathFactory.newInstance().newXPath().evaluate(path, this)

@Suppress("UNCHECKED_CAST")
fun json(text: String): Map<String, Any?> = JsonSlurper().parseText(text) as Map<String, Any?>

fun consumerReportDirectory(directory: File, kind: String): File =
	directory.resolve(if (kind == "gradle") "build/test-results/test" else "target/surefire-reports")

fun successfulConsumerReports(directory: File, kind: String): List<File> {
	val reports = consumerReportDirectory(directory, kind)
		.listFiles { file -> file.name.startsWith("TEST-") && file.extension == "xml" }?.toList().orEmpty()
	check(reports.isNotEmpty()) { "Missing $kind consumer test reports" }
	val expectedClass = if (kind == "core") "verification.PublishedCoreTests" else "verification.PublishedStarterTests"
	val suites = reports.map { xml(it) }
	check(suites.all { suite ->
		listOf("failures", "errors", "skipped").all { suite.value("/testsuite/@$it").toIntOrNull() == 0 } &&
			suite.value("/testsuite/@tests").toIntOrNull()?.let { it > 0 } == true
	}) { "Incomplete or unsuccessful $kind consumer test suite" }
	check(suites.any {
		it.value("count(/testsuite/testcase[@classname='$expectedClass'][not(failure) and not(error) and not(skipped)])").toDouble() > 0
	}) { "Expected $kind consumer tests did not execute" }
	return reports
}

val verifyConsumerReportValidation = tasks.register("verifyConsumerReportValidation") {
	group = "verification"
	description = "Checks that consumer verification rejects missing, empty, skipped, or unsuccessful test reports"
	mustRunAfter(tasks.named("clean"))
	doLast {
		for (kind in listOf("gradle", "maven", "core")) {
			val directory = layout.buildDirectory.dir("consumer-report-validation/$kind").get().asFile
			val reportDirectory = consumerReportDirectory(directory, kind)
			reportDirectory.deleteRecursively()
			reportDirectory.mkdirs()
			val report = reportDirectory.resolve("TEST-consumer.xml")
			val expectedClass = if (kind == "core") "verification.PublishedCoreTests" else "verification.PublishedStarterTests"
			val valid = """<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="$expectedClass" name="delivery"/></testsuite>"""
			report.writeText(valid)
			successfulConsumerReports(directory, kind)
			val invalidReports = listOf(
				valid.replace("tests=\"1\"", "tests=\"0\"").replace(Regex("<testcase[^>]*/>"), ""),
				valid.replace(expectedClass, "verification.UnrelatedTests"),
				valid.replace(Regex("<testcase[^>]*/>"), ""),
				valid.replace("skipped=\"0\"", "skipped=\"1\"").replace("/>", "><skipped/></testcase>"),
				valid.replace("failures=\"0\"", "failures=\"1\"").replace("/>", "><failure/></testcase>"),
				valid.replace("errors=\"0\"", "errors=\"1\"").replace("/>", "><error/></testcase>")
			)
			for (invalid in invalidReports) {
				report.writeText(invalid)
				check(runCatching { successfulConsumerReports(directory, kind) }.exceptionOrNull() is IllegalStateException) {
					"Consumer verification accepted an invalid $kind test report"
				}
			}
			report.delete()
			check(runCatching { successfulConsumerReports(directory, kind) }.exceptionOrNull() is IllegalStateException) {
				"Consumer verification accepted missing $kind test reports"
			}
		}
	}
}

tasks.named("check") { dependsOn(verifyConsumerReportValidation) }

val verifyPublication = tasks.register("verifyPublication") {
	group = "verification"
	description = "Checks published JARs, POMs, Gradle metadata, configuration metadata, and checksums"
	dependsOn(modules.map { ":$it:publishAllPublicationsToVerificationRepository" })
	mustRunAfter(tasks.named("clean"))
	doLast {
		val checksums = mutableListOf<String>()
		val repositoryDir = repository.get().asFile
		for (module in modules) {
			val directory = repositoryDir.resolve("${publicationGroup.replace('.', '/')}/$module/$publicationVersion")
			fun artifact(extension: String, classifier: String = ""): File {
				val version = if (publicationVersion.endsWith("-SNAPSHOT")) {
					xml(directory.resolve("maven-metadata.xml")).value(
						"/metadata/versioning/snapshotVersions/snapshotVersion[extension='$extension' and " +
							if (classifier.isEmpty()) "not(classifier)]/value" else "classifier='$classifier']/value"
					).also { check(it.isNotBlank()) { "Missing snapshot metadata for $module $extension $classifier" } }
				}
				else publicationVersion
				return directory.resolve("$module-$version${if (classifier.isEmpty()) "" else "-$classifier"}.$extension")
			}
			for ((extension, classifier) in listOf("jar" to "", "jar" to "sources", "jar" to "javadoc", "pom" to "", "module" to "")) {
				val file = artifact(extension, classifier)
				check(file.isFile) { "Missing publication artifact: ${file.name}" }
				val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
				check(file.resolveSibling("${file.name}.sha256").readText().trim() == digest) {
					"Published SHA-256 mismatch: ${file.name}"
				}
				checksums += "$digest  ${file.relativeTo(repositoryDir).invariantSeparatorsPath}"
			}
			val pom = xml(artifact("pom"))
			for (field in listOf("licenses/license/name", "scm/connection", "developers/developer/id")) {
				check(pom.value("/project/$field").isNotBlank()) { "Missing POM identity metadata in $module: $field" }
			}
			val metadata = json(artifact("module").readText())
			check((metadata["component"] as Map<*, *>)["version"] == publicationVersion) { "Unexpected Gradle module version" }
			for (variant in metadata["variants"] as List<*>) {
				for (dependency in (variant as Map<*, *>)["dependencies"] as? List<*> ?: emptyList<Any>()) {
					val version = (dependency as Map<*, *>)["version"] as? Map<*, *>
					check(!(version?.get("requires") as? String).isNullOrBlank()) { "Unresolved published dependency in $module" }
				}
			}
			JarFile(artifact("jar")).use { jar ->
				for (entry in jar.entries().asSequence().filter { it.name.endsWith(".class") }) {
					val bytes = jar.getInputStream(entry).use { it.readNBytes(8) }
					check(bytes.size == 8 && bytes[6].toInt() == 0 && bytes[7].toInt() == 61) {
						"Published bytecode must target Java 17: ${entry.name}"
					}
				}
				if (module.endsWith("autoconfigure")) {
					fun resource(name: String): String = jar.getInputStream(jar.getJarEntry(name)).bufferedReader().use { it.readText() }
					check(resource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
						.contains("McpSigV4AutoConfiguration")) { "Missing auto-configuration registration" }
					val properties = (json(resource("META-INF/spring-configuration-metadata.json"))["properties"] as List<*>)
						.map { it as Map<*, *> }.associateBy { it["name"] }
					val prefix = "mcp.sigv4.connections"
					check(!(properties[prefix]?.get("description") as? String).isNullOrBlank()) { "Missing connection-map description" }
					for ((name, default) in mapOf("region" to null, "service-name" to "bedrock-agentcore",
						"allow-insecure-http" to false, "signing.additional-unsigned-headers" to emptyList<String>())) {
						val property = properties["$prefix.*.$name"]
						check(!(property?.get("description") as? String).isNullOrBlank() && property?.get("defaultValue") == default) {
							"Missing or incorrect generated property metadata: $name"
						}
					}
				}
			}
		}
		layout.buildDirectory.file("verification-artifacts.sha256").get().asFile.writeText(checksums.joinToString("\n", postfix = "\n"))
	}
}

val verifyConsumers = tasks.register("verifyConsumers") {
	group = "verification"
	description = "Runs independent Maven/Gradle consumers of the published starter and core without AWS"
	dependsOn(verifyPublication, verifyConsumerReportValidation)
}

var previousMavenConsumer: TaskProvider<Exec>? = null
for (kind in listOf("gradle", "maven", "core")) {
	val target = consumerRoot.map { it.dir(kind) }
	val suffix = kind.replaceFirstChar { it.uppercase() }
	val prepare = tasks.register<Sync>("prepare${suffix}Consumer") {
		from("verification/$kind-consumer")
		if (kind != "core") from("verification/consumer/src") { into("src") }
		into(target)
		// A fresh fixture prevents stale test outputs from satisfying consumer verification.
		outputs.upToDateWhen { false }
		mustRunAfter(tasks.named("clean"))
	}
	val precedingMavenConsumer = previousMavenConsumer
	val clearCache = if (kind == "gradle") null else tasks.register<Delete>("clear${suffix}ConsumerCache") {
		// Maven -U does not refresh release artifacts. Evict only this library version.
		delete(modules.map { consumerRoot.map { dir ->
			dir.file("maven-local/${publicationGroup.replace('.', '/')}/$it/$publicationVersion")
		} })
		mustRunAfter(tasks.named("clean"))
		precedingMavenConsumer?.let { mustRunAfter(it) }
	}
	val consumer = tasks.register<Exec>("verify${suffix}Consumer") {
		group = "verification"
		description = "Verifies the independent $kind consumer against published artifacts"
		dependsOn(verifyPublication, prepare)
		if (clearCache != null) dependsOn(clearCache)
		val properties = mapOf("verificationRepository" to repository.get().asFile.toURI().toString(),
			"sigv4Group" to publicationGroup, "sigv4Version" to publicationVersion, "bootVersion" to bootVersion)
		val directory = target.get().asFile
		val log = directory.resolve("verification.log")
		workingDir(rootDir)
		if (kind == "gradle") {
			commandLine(listOf(rootProject.file("gradlew").absolutePath, "--no-daemon", "--no-build-cache",
				"--refresh-dependencies", "--project-dir", directory.absolutePath, "test") + properties.map { "-P${it.key}=${it.value}" })
		}
		else {
			commandLine(listOf("mvn", "-U", "--batch-mode", "--no-transfer-progress",
				"-Dmaven.repo.local=${consumerRoot.get().asFile.resolve("maven-local")}", "-f", directory.resolve("pom.xml").absolutePath,
				"verify") + properties.map { "-D${it.key}=${it.value}" })
		}
		isIgnoreExitValue = true
		doFirst {
			environment("JAVA_HOME", launcher.get().metadata.installationPath.asFile.absolutePath)
			standardOutput = log.outputStream()
			errorOutput = standardOutput
		}
		doLast {
			standardOutput.close()
			check(executionResult.get().exitValue == 0) { "$kind consumer failed; see $log" }
			val reports = successfulConsumerReports(directory, kind)
			if (kind == "core") {
				for (report in reports) {
					val classpath = xml(report).value("/testsuite/properties/property[@name='java.class.path']/@value").replace('\\', '/')
					check(classpath.isNotBlank()) { "Missing core consumer runtime classpath" }
					check(listOf("/org/springframework/ai/", "/org/springframework/boot/", "jackson-databind", "mcp-json-jackson")
						.none { it in classpath }) { "Core consumer unexpectedly requires Spring AI, Boot, or a JSON mapper" }
				}
			}
		}
	}
	if (kind != "gradle") previousMavenConsumer = consumer
	verifyConsumers.configure { dependsOn(consumer) }
}
