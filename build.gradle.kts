import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.plugins.signing.SigningExtension
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
	base
	alias(libs.plugins.spring.dependency.management) apply false
	alias(libs.plugins.spring.javaformat) apply false
	alias(libs.plugins.errorprone) apply false
}

val publishedModules = setOf(
	"spring-ai-mcp-sigv4",
	"spring-ai-mcp-sigv4-spring-boot-autoconfigure",
	"spring-ai-mcp-sigv4-spring-boot-starter"
)

subprojects {
	apply(plugin = "java-library")
	apply(plugin = "io.spring.dependency-management")
	apply(plugin = "io.spring.javaformat")
	apply(plugin = "net.ltgt.errorprone")
	apply(plugin = "checkstyle")

	group = providers.gradleProperty("projectGroup").get()
	version = providers.gradleProperty("projectVersion").get()

	configure<DependencyManagementExtension> {
		imports {
			mavenBom(
				rootProject.libs.spring.boot.bom
					.map { "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}" }
					.get()
			)
			mavenBom(
				rootProject.libs.spring.ai.bom
					.map { "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}" }
					.get()
			)
			mavenBom(
				rootProject.libs.aws.sdk.bom
					.map { "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}" }
					.get()
			)
			mavenBom(
				rootProject.libs.mcp.bom
					.map { "${it.module.group}:${it.module.name}:${it.versionConstraint.requiredVersion}" }
					.get()
			)
		}
	}

	extensions.configure<JavaPluginExtension> {
		toolchain {
			languageVersion = JavaLanguageVersion.of(17)
		}
		withSourcesJar()
		withJavadocJar()
	}

	dependencies {
		if (name != "spring-ai-mcp-sigv4-spring-boot-starter") {
			"compileOnlyApi"(rootProject.libs.jspecify)
		}
		"errorprone"(rootProject.libs.errorprone.core)
		"errorprone"(rootProject.libs.nullaway)
		"checkstyle"(rootProject.libs.checkstyle)
		"checkstyle"(rootProject.libs.spring.javaformat.checkstyle)
		"testImplementation"(rootProject.libs.spring.boot.starter.test)
		"testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
	}

	configure<CheckstyleExtension> {
		configFile = rootProject.file("src/checkstyle/checkstyle.xml")
		configProperties["checkstyle.header.file"] = rootProject.file("src/checkstyle/checkstyle-header.txt")
		configProperties["checkstyle.suppressions.file"] = rootProject.file("src/checkstyle/checkstyle-suppressions.xml")
		isShowViolations = true
	}

	tasks.withType<Checkstyle>().configureEach {
		reports {
			xml.required = true
			html.required = true
		}
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform()
		javaLauncher = project.extensions.getByType<JavaToolchainService>().launcherFor {
			languageVersion = JavaLanguageVersion.of(providers.gradleProperty("testJavaVersion").orElse("17").get().toInt())
		}
	}

	tasks.named<Test>("test") {
		useJUnitPlatform {
			excludeTags("integration")
		}
	}

	tasks.withType<JavaCompile>().configureEach {
		options.encoding = "UTF-8"
		options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
		if (name == "compileJava") {
			options.compilerArgs.addAll(
				listOf("-XDcompilePolicy=simple", "-XDaddTypeAnnotationsToSymbol=true", "--should-stop=ifError=FLOW")
			)
			options.errorprone {
				disableAllChecks = true
				error("StringCaseLocaleUsage", "NullAway")
				option("NullAway:OnlyNullMarked", "true")
				option("NullAway:JSpecifyMode", "true")
			}
		}
		else {
			options.errorprone.enabled = false
		}
	}

	tasks.withType<Javadoc>().configureEach {
		(options as StandardJavadocDocletOptions).apply {
			encoding = "UTF-8"
			charSet = "UTF-8"
			addStringOption("Xdoclint:all,-missing", "-quiet")
			addBooleanOption("Werror", true)
		}
	}

	tasks.withType<AbstractArchiveTask>().configureEach {
		isPreserveFileTimestamps = false
		isReproducibleFileOrder = true
	}

	if (name in publishedModules) {
		apply(plugin = "maven-publish")
		apply(plugin = "signing")

		// Verification publications share the root build directory. Root clean must
		// finish before parallel subproject publishers create files there.
		tasks.withType<PublishToMavenRepository>().configureEach {
			mustRunAfter(rootProject.tasks.named("clean"))
		}

		extensions.configure<PublishingExtension> {
			repositories {
				maven {
					name = "verification"
					url = rootProject.layout.buildDirectory.dir("verification-repository").get().asFile.toURI()
				}
			}
			publications {
				create<MavenPublication>("mavenJava") {
					from(components["java"])
					versionMapping {
						usage("java-api") { fromResolutionOf("runtimeClasspath") }
						usage("java-runtime") { fromResolutionResult() }
					}
					pom {
						name.set(project.name)
						description.set(providers.provider { project.description ?: project.name })
						url.set(providers.gradleProperty("projectUrl"))
						licenses {
							license {
								name.set("The Apache License, Version 2.0")
								url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
								distribution.set("repo")
							}
						}
						developers {
							developer {
								id.set(providers.gradleProperty("developerId"))
								name.set(providers.gradleProperty("developerName"))
							}
						}
						scm {
							url.set(providers.gradleProperty("projectUrl"))
							connection.set(providers.gradleProperty("scmConnection"))
							developerConnection.set(providers.gradleProperty("scmDeveloperConnection"))
						}
					}
				}
			}

			val repositoryUrl = providers.environmentVariable("MAVEN_REPOSITORY_URL")
			if (repositoryUrl.isPresent) {
				repositories {
					maven {
						name = "release"
						url = uri(repositoryUrl.get())
						credentials {
							username = providers.environmentVariable("MAVEN_REPOSITORY_USERNAME").orNull
							password = providers.environmentVariable("MAVEN_REPOSITORY_PASSWORD").orNull
						}
					}
				}
			}
		}

		configure<SigningExtension> {
			val signingKey = providers.environmentVariable("PGP_SIGNING_KEY")
			val signingPassword = providers.environmentVariable("PGP_SIGNING_PASSWORD")
			if (signingKey.isPresent) {
				useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
				sign(extensions.getByType<PublishingExtension>().publications)
			}
		}
	}
}

tasks.named("check") {
	dependsOn(subprojects.map { "${it.path}:check" })
	dependsOn("verifySourceHeaders")
	dependsOn("verifyConsumers")
}

tasks.register<Exec>("verifyConsumers") {
	description = "Verifies publication metadata and real Maven/Gradle starter consumers without AWS"
	group = LifecycleBasePlugin.VERIFICATION_GROUP
	dependsOn(publishedModules.map { ":$it:publishAllPublicationsToVerificationRepository" })
	mustRunAfter(tasks.named("clean"))
	val launcher = project(":spring-ai-mcp-sigv4").extensions.getByType<JavaToolchainService>().launcherFor {
		languageVersion = JavaLanguageVersion.of(providers.gradleProperty("testJavaVersion").orElse("17").get().toInt())
	}
	doFirst {
		environment("JAVA_HOME", launcher.get().metadata.installationPath.asFile.absolutePath)
	}
	commandLine("python3", "verification/verify_consumers.py", "--group", providers.gradleProperty("projectGroup").get(),
		"--version", providers.gradleProperty("projectVersion").get(),
		"--boot", libs.versions.spring.boot.get())
}

tasks.register("verifySourceHeaders") {
	val javaSources = fileTree(rootDir) {
		include("**/*.java")
		exclude("**/build/**")
	}
	inputs.files(javaSources)
	doLast {
		val missingHeaders = javaSources.files.filterNot {
			it.readText().startsWith("/*\n * Copyright 2026-present")
		}
		check(missingHeaders.isEmpty()) {
			"Java files missing the project source header: ${missingHeaders.joinToString()}"
		}
	}
}
