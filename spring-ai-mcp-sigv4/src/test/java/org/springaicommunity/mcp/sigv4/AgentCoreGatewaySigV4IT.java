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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration test against an IAM-secured MCP Streamable HTTP gateway (e.g. Amazon
 * Bedrock AgentCore Gateway).
 *
 * <p>
 * Runs only when AWS credentials and {@code MCP_GW_URL} are present. Optional:
 * {@code MCP_GW_ENDPOINT} (default {@code /mcp}), {@code MCP_IAM_AUTH_SERVICE} (default
 * {@code bedrock-agentcore}), {@code MCP_IAM_IT_TOOL_NAME},
 * {@code MCP_IAM_IT_TOOL_ARGUMENTS_JSON}, {@code MCP_IAM_IT_EXPECTED_RESULT_JSON}. Set
 * {@code MCP_IAM_IT_TRACING=true} with a real Java agent to require an active trace and
 * verify the late-bound header signing policy against the live gateway.
 * </p>
 */
@Tag("integration")
class AgentCoreGatewaySigV4IT {

	private static String gatewayUrl;

	private static String endpoint;

	private static String serviceName;

	@BeforeAll
	static void setUp() {
		gatewayUrl = System.getenv("MCP_GW_URL");
		assumeTrue(gatewayUrl != null && !gatewayUrl.isBlank(), "MCP_GW_URL is not set");

		String configuredEndpoint = System.getenv("MCP_GW_ENDPOINT");
		endpoint = (configuredEndpoint == null || configuredEndpoint.isBlank()) ? "/mcp" : configuredEndpoint;

		String configuredServiceName = System.getenv("MCP_IAM_AUTH_SERVICE");
		serviceName = (configuredServiceName == null || configuredServiceName.isBlank()) ? "bedrock-agentcore"
				: configuredServiceName;
	}

	@Test
	@SuppressWarnings("try")
	void initializesListsAndCallsToolsWithSigV4() {
		boolean tracing = Boolean.parseBoolean(System.getenv("MCP_IAM_IT_TRACING"));
		Span span = tracing
				? GlobalOpenTelemetry.getTracer("sigv4-live-test").spanBuilder("gateway-verification").startSpan()
				: Span.getInvalid();
		if (tracing) {
			assertThat(span.isRecording()).as("real agent must record the live verification span").isTrue();
		}
		Context traceContext = Baggage.builder()
			.put("sigv4-test", "live")
			.build()
			.storeInContext(span.storeInContext(Context.current()));
		Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
		AtomicInteger sessionRequests = new AtomicInteger();
		try (Scope scope = traceContext.makeCurrent();
				var credentialsProvider = DefaultCredentialsProvider.builder().build()) {
			var region = DefaultAwsRegionProviderChain.builder().build().getRegion();
			URI resolvedEndpoint = URI.create(gatewayUrl).resolve(endpoint).normalize();
			var signer = new AwsSigV4McpRequestCustomizer(credentialsProvider, region, serviceName, resolvedEndpoint);
			var transport = HttpClientStreamableHttpTransport.builder(gatewayUrl)
				.endpoint(endpoint)
				.asyncHttpRequestCustomizer((builder, method, uri, body, context) -> {
					if (tracing) {
						GlobalOpenTelemetry.getPropagators()
							.getTextMapPropagator()
							.inject(traceContext, builder, (carrier, name, value) -> carrier.setHeader(name, value));
						assertThat(builder.build().headers().firstValue("traceparent").isPresent()).isTrue();
					}
					return Mono.from(signer.customize(builder, method, uri, body, context)).doOnNext(signed -> {
						var headers = signed.build().headers();
						if (tracing) {
							String authorization = headers.firstValue("Authorization").orElseThrow();
							String signedHeaders = authorization.split("SignedHeaders=", 2)[1].split(",", 2)[0];
							assertThat(signedHeaders.contains("traceparent") || signedHeaders.contains("baggage"))
								.as("propagation headers must be excluded from the signature")
								.isFalse();
							assertThat(headers.firstValue("traceparent").isPresent()).isTrue();
							assertThat(headers.firstValue("baggage").isPresent()).isTrue();
						}
						requests.computeIfAbsent(method, key -> new AtomicInteger()).incrementAndGet();
						if (headers.firstValue("Mcp-Session-Id").isPresent()) {
							sessionRequests.incrementAndGet();
						}
					});
				})
				.build();
			var client = McpClient.sync(transport).build();

			try {
				McpSchema.InitializeResult initialized = client.initialize();
				assertThat(initialized).isNotNull();
				assertThat(initialized.serverInfo()).isNotNull();
				assertThat(initialized.serverInfo().name()).isNotBlank();

				McpSchema.ListToolsResult tools = client.listTools();
				assertThat(tools).isNotNull();
				assertThat(tools.tools()).isNotEmpty();

				invokeTool(client, tools.tools());
			}
			finally {
				client.closeGracefully();
			}
		}
		finally {
			span.end();
			// Only aggregate protocol coverage is emitted; no request or response values.
			System.out.println("SIGV4_LIVE tracing=" + tracing + " requests=" + requests + " sessionRequests="
					+ sessionRequests.get());
		}
	}

	private static void invokeTool(McpSyncClient client, List<McpSchema.Tool> discoveredTools) {
		String configuredToolName = System.getenv("MCP_IAM_IT_TOOL_NAME");
		String argumentsJson = System.getenv("MCP_IAM_IT_TOOL_ARGUMENTS_JSON");
		assumeTrue(configuredToolName != null && !configuredToolName.isBlank(), "MCP_IAM_IT_TOOL_NAME is not set");
		assumeTrue(argumentsJson != null && !argumentsJson.isBlank(), "MCP_IAM_IT_TOOL_ARGUMENTS_JSON is not set");

		String toolName = resolveToolName(configuredToolName, discoveredTools);
		McpSchema.CallToolResult result = client
			.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(readArguments(argumentsJson)).build());
		assertThat(result).isNotNull();
		assertThat(Boolean.TRUE.equals(result.isError())).isFalse();
		String expectedResultJson = System.getenv("MCP_IAM_IT_EXPECTED_RESULT_JSON");
		if (expectedResultJson != null && !expectedResultJson.isBlank()) {
			var expected = readArguments(expectedResultJson);
			boolean matches = expected.equals(result.structuredContent()) || result.content()
				.stream()
				.filter(McpSchema.TextContent.class::isInstance)
				.map(McpSchema.TextContent.class::cast)
				.anyMatch(content -> expected.equals(readArguments(content.text())));
			assertThat(matches).as("tool response must match the configured expected JSON").isTrue();
		}
	}

	private static String resolveToolName(String configuredToolName, List<McpSchema.Tool> discoveredTools) {
		List<String> matches = discoveredTools.stream()
			.map(McpSchema.Tool::name)
			.filter(name -> name.equals(configuredToolName) || name.endsWith("___" + configuredToolName))
			.toList();
		assertThat(matches).as("configured Gateway tool name or unique target-prefixed tool name").hasSize(1);
		return matches.get(0);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> readArguments(String argumentsJson) {
		try {
			return new ObjectMapper().readValue(argumentsJson, Map.class);
		}
		catch (Exception ex) {
			// Jackson exceptions may include tool input or response contents.
			throw new AssertionError("Live tool arguments, expected results, and text results must be JSON objects");
		}
	}

}
