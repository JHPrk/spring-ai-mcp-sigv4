/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.mcp.sigv4.autoconfigure;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.sigv4.AwsSigV4HeaderSigningPolicies;
import org.springaicommunity.mcp.sigv4.AwsSigV4HeaderSigningPolicy;
import org.springaicommunity.mcp.sigv4.RoutingAwsSigV4McpRequestCustomizer;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class McpHeaderSigningPolicyTests {

	private static final String AWS = McpAwsProperties.CONFIG_PREFIX + ".connections.";

	private static final String TRANSPORT = "spring.ai.mcp.client.streamable-http.connections.";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(McpSigV4AutoConfiguration.class))
		.withBean(AwsCredentialsProvider.class, () -> () -> AwsBasicCredentials.create("test", "test-secret"))
		.withPropertyValues(TRANSPORT + "first.url=https://first.example", AWS + "first.region=us-east-1");

	@Test
	void usesDefaultPolicyWhenNoExclusionsConfigured() {
		this.runner.run(context -> {
			assertThat(context).hasSingleBean(AwsSigV4HeaderSigningPolicy.class);
			HttpRequest request = sign(context.getBean(RoutingAwsSigV4McpRequestCustomizer.class), "first");
			assertThat(signedHeaders(request)).contains("x-first", "x-second", "x-stable")
				.doesNotContain("traceparent");
		});
	}

	@Test
	void composesDifferentAdditionalExclusionsPerConnection() {
		this.runner
			.withPropertyValues(TRANSPORT + "second.url=https://second.example", AWS + "second.region=us-east-1",
					AWS + "first.signing.additional-unsigned-headers[0]=X-First",
					AWS + "second.signing.additional-unsigned-headers[0]=X-Second")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var router = context.getBean(RoutingAwsSigV4McpRequestCustomizer.class);
				HttpRequest first = sign(router, "first");
				HttpRequest second = sign(router, "second");
				assertThat(signedHeaders(first)).contains("x-second", "x-stable")
					.doesNotContain("traceparent", "x-first");
				assertThat(signedHeaders(second)).contains("x-first", "x-stable")
					.doesNotContain("traceparent", "x-second");
				assertThat(first.headers().firstValue("X-First")).contains("first");
				assertThat(second.headers().firstValue("X-Second")).contains("second");
			});
	}

	@Test
	void applicationPolicyReplacesBaseButRetainsConnectionExclusions() {
		AwsSigV4HeaderSigningPolicy base = AwsSigV4HeaderSigningPolicies.excluding(List.of("X-First"));
		this.runner.withBean(AwsSigV4HeaderSigningPolicy.class, () -> base)
			.withPropertyValues(AWS + "first.signing.additional-unsigned-headers[0]=X-Second")
			.run(context -> {
				assertThat(context).hasSingleBean(AwsSigV4HeaderSigningPolicy.class);
				assertThat(context.getBean(AwsSigV4HeaderSigningPolicy.class)).isSameAs(base);
				HttpRequest request = sign(context.getBean(RoutingAwsSigV4McpRequestCustomizer.class), "first");
				assertThat(signedHeaders(request)).contains("traceparent", "x-stable")
					.doesNotContain("x-first", "x-second");
			});
	}

	@Test
	void rejectsDifferentExclusionsForAliasesOfSameEndpoint() {
		this.runner
			.withPropertyValues(TRANSPORT + "alias.url=https://first.example", AWS + "alias.region=us-east-1",
					AWS + "alias.signing.additional-unsigned-headers[0]=X-First")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseMessage(
						"MCP connections resolving to the same endpoint have conflicting AWS signing scopes");
			});
	}

	@Test
	void acceptsEquivalentNormalizedExclusionsForEndpointAliases() {
		this.runner
			.withPropertyValues(TRANSPORT + "alias.url=https://first.example", AWS + "alias.region=us-east-1",
					AWS + "alias.signing.additional-unsigned-headers[0]=X-First",
					AWS + "first.signing.additional-unsigned-headers[0]=x-first")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(signedHeaders(sign(context.getBean(RoutingAwsSigV4McpRequestCustomizer.class), "first")))
					.doesNotContain("x-first", "traceparent");
			});
	}

	private static HttpRequest sign(RoutingAwsSigV4McpRequestCustomizer router, String connection) {
		URI endpoint = URI.create("https://" + connection + ".example/mcp");
		var builder = HttpRequest.newBuilder(endpoint)
			.GET()
			.header("TraceParent", "parent")
			.header("X-First", "first")
			.header("X-Second", "second")
			.header("X-Stable", "stable");
		return Mono.from(router.customize(builder, "GET", endpoint, null, McpTransportContext.EMPTY)).block().build();
	}

	private static List<String> signedHeaders(HttpRequest request) {
		String authorization = request.headers().firstValue("Authorization").orElseThrow();
		return List.of(authorization.split("SignedHeaders=", 2)[1].split(",", 2)[0].split(";"));
	}

}
