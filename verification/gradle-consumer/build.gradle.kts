plugins { java }
repositories {
    maven {
        url = uri(providers.gradleProperty("verificationRepository").get())
        content { includeGroup(providers.gradleProperty("sigv4Group").get()) }
    }
    mavenCentral()
}
dependencies {
    implementation("${providers.gradleProperty("sigv4Group").get()}:spring-ai-mcp-sigv4-spring-boot-starter:${providers.gradleProperty("sigv4Version").get()}")
    testImplementation("org.springframework.boot:spring-boot-starter-test:${providers.gradleProperty("bootVersion").get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<JavaCompile>().configureEach { options.release = 17 }
tasks.test { useJUnitPlatform() }
