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

package org.springaicommunity.mcp.sigv4.starter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpAsyncClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises Boot auto-configuration and the standard ToolCallback provider against AWS.
 */
@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
class AgentCoreGatewayStarterIT {

	@Test
	@SuppressWarnings("unchecked")
	void discoversAndCallsGatewayToolsThroughAutoConfiguredStarter(CapturedOutput output) {
		String url = System.getenv("MCP_GW_URL");
		assumeTrue(url != null && !url.isBlank(), "MCP_GW_URL is not set");
		String toolName = System.getenv("MCP_IAM_IT_TOOL_NAME");
		String arguments = System.getenv("MCP_IAM_IT_TOOL_ARGUMENTS_JSON");
		assertThat(toolName != null && !toolName.isBlank()).as("live tool name is required").isTrue();
		assertThat(arguments != null && !arguments.isBlank()).as("live arguments are required").isTrue();
		String region = System.getenv("AWS_REGION");
		assertThat(region != null && !region.isBlank()).as("AWS_REGION is required").isTrue();
		AwsCredentials credentials;
		try (var context = application(url, System.getenv("MCP_GW_ENDPOINT"), region).run()) {
			List<McpAsyncClient> clients = context.getBean("mcpAsyncClients", List.class);
			assertThat(clients).hasSize(1);
			var client = clients.get(0);
			assertThat(client.getCurrentInitializationResult() != null).as("MCP initialization").isTrue();
			var tools = client.listTools().block(Duration.ofSeconds(30));
			assertThat(tools != null && !tools.tools().isEmpty()).as("MCP tool discovery").isTrue();
			var provider = context.getBean(AsyncMcpToolCallbackProvider.class);
			List<ToolCallback> callbacks = List.of(provider.getToolCallbacks())
				.stream()
				.filter(callback -> callback.getToolDefinition().name().equals(toolName)
						|| callback.getToolDefinition().name().endsWith("___" + toolName))
				.toList();
			assertThat(callbacks.size()).as("unique configured Gateway callback").isEqualTo(1);
			String response = callbacks.get(0).call(arguments);
			assertEchoResponse(response, arguments);
			credentials = context.getBean(AwsCredentialsProvider.class).resolveCredentials();
			client.closeGracefully().block(Duration.ofSeconds(30));
		}
		assertThat(output.getAll().contains(credentials.accessKeyId())).as("access key redacted").isFalse();
		assertThat(output.getAll().contains(credentials.secretAccessKey())).as("secret key redacted").isFalse();
		if (credentials instanceof AwsSessionCredentials session) {
			assertThat(output.getAll().contains(session.sessionToken())).as("session token redacted").isFalse();
		}
		assertThat(output.getAll().contains("AWS4-HMAC-SHA256")).as("signed authorization redacted").isFalse();
	}

	static SpringApplicationBuilder application(String url, String configuredEndpoint, String region) {
		String endpoint = StringUtils.hasText(configuredEndpoint) ? configuredEndpoint : "/mcp";
		return new SpringApplicationBuilder(LiveApplication.class).web(WebApplicationType.NONE)
			.properties(Map.of("spring.main.banner-mode", "off", "logging.level.root", "OFF",
					"spring.ai.mcp.client.type", "ASYNC", "spring.ai.mcp.client.request-timeout", "30s",
					"spring.ai.mcp.client.streamable-http.connections.agentcore.url", url,
					"spring.ai.mcp.client.streamable-http.connections.agentcore.endpoint", endpoint,
					"mcp.sigv4.connections.agentcore.region", region));
	}

	static void assertEchoResponse(String response, String arguments) {
		var mapper = new ObjectMapper();
		try {
			Map<?, ?> input = mapper.readValue(arguments, Map.class);
			Object message = input == null ? null : input.get("message");
			assertThat(message instanceof String).as("live echo message must be a string").isTrue();
			List<?> content = mapper.readValue(response, List.class);
			// ToolCallback content can omit the MCP wire type discriminator.
			boolean matches = content != null && content.stream()
				.anyMatch(item -> item instanceof Map<?, ?> block
						&& (!block.containsKey("type") || "text".equals(block.get("type")))
						&& block.get("text") instanceof String text && matchesEcho(mapper, text, message));
			assertThat(matches).as("Gateway JSON echo must match the message argument").isTrue();
		}
		catch (JacksonException ex) {
			// Parser exceptions can include tool inputs or results.
			throw new AssertionError("Live echo arguments and callback content must be valid JSON");
		}
	}

	private static boolean matchesEcho(ObjectMapper mapper, String text, Object message) {
		try {
			Map<?, ?> result = mapper.readValue(text, Map.class);
			return result != null && message.equals(result.get("echo"));
		}
		catch (JacksonException ex) {
			// Other text blocks can contain explanations instead of JSON.
			return false;
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	static class LiveApplication {

	}

}
