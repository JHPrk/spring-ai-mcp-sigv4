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

package org.springaicommunity.mcp.sigv4;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;

import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingAwsSigV4McpRequestCustomizerTests {

	private static final URI AWS_ENDPOINT = URI.create("https://gateway.example.com/mcp");

	private final RoutingAwsSigV4McpRequestCustomizer customizer = new RoutingAwsSigV4McpRequestCustomizer(
			Map.of(AWS_ENDPOINT, new AwsSigV4McpRequestCustomizer(() -> AwsBasicCredentials.create("AKID", "secret"),
					Region.US_EAST_1, "bedrock-agentcore", AWS_ENDPOINT)));

	@Test
	void signsConfiguredEndpoint() {
		HttpRequest request = customize(AWS_ENDPOINT);

		assertThat(request.headers().firstValue("Authorization"))
			.hasValueSatisfying(value -> assertThat(value).contains("/us-east-1/bedrock-agentcore/aws4_request"));
	}

	@Test
	void leavesUnconfiguredEndpointUnchanged() {
		HttpRequest request = customize(URI.create("https://public.example.com/mcp"));

		assertThat(request.headers().firstValue("Authorization")).isEmpty();
	}

	@Test
	void runsAfterOtherOrderedRequestCustomizers() {
		assertThat(this.customizer.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
	}

	@Test
	void diagnosticStringDoesNotExposeEndpoints() {
		assertThat(this.customizer.toString()).isEqualTo("RoutingAwsSigV4McpRequestCustomizer[endpointCount=1]")
			.doesNotContain("gateway.example.com");
	}

	@Test
	void duplicateEndpointFailureDoesNotExposeEndpoint() {
		URI equivalentEndpoint = URI.create("https://private.example.com/base/../mcp");
		URI normalizedEndpoint = equivalentEndpoint.normalize();
		var signer = new AwsSigV4McpRequestCustomizer(() -> AwsBasicCredentials.create("AKID", "secret"),
				Region.US_EAST_1, "bedrock-agentcore", normalizedEndpoint);

		assertThatThrownBy(() -> new RoutingAwsSigV4McpRequestCustomizer(
				Map.of(equivalentEndpoint, signer, normalizedEndpoint, signer)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("duplicate normalized MCP endpoint")
			.hasMessageNotContaining("private.example.com");
	}

	private HttpRequest customize(URI endpoint) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).GET();
		return Mono.from(this.customizer.customize(builder, "GET", endpoint, null, McpTransportContext.EMPTY))
			.block()
			.build();
	}

}
