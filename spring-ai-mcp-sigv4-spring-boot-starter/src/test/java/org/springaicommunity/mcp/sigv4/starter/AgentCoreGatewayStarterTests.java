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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import tools.jackson.databind.ObjectMapper;

import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCoreGatewayStarterTests {

	private final ObjectMapper mapper = new ObjectMapper();

	private final List<String> paths = new CopyOnWriteArrayList<>();

	private final List<Boolean> signatures = new CopyOnWriteArrayList<>();

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "/custom", "/nested/mcp" })
	void liveApplicationUsesConfiguredOrDefaultEndpointAndInvokesJsonEcho(String configuredEndpoint) throws Exception {
		String url = startServer();
		String message = "안녕하세요 \"quoted\"\\path\nnext";
		String arguments = this.mapper.writeValueAsString(Map.of("message", message));
		try (var context = AgentCoreGatewayStarterIT.application(url, configuredEndpoint, "us-east-1")
			.sources(TestCredentials.class)
			.properties(Map.of("mcp.sigv4.connections.agentcore.allow-insecure-http", "true"))
			.run()) {
			var callbacks = context.getBean(AsyncMcpToolCallbackProvider.class).getToolCallbacks();
			assertThat(callbacks).hasSize(1);
			AgentCoreGatewayStarterIT.assertEchoResponse(callbacks[0].call(arguments), arguments);
		}
		String expected = configuredEndpoint == null || configuredEndpoint.isBlank() ? "/mcp" : configuredEndpoint;
		assertThat(this.paths).isNotEmpty().containsOnly(expected);
		assertThat(this.signatures).isNotEmpty().containsOnly(true);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "hello", "안녕하세요 \"quoted\"\\path\nnext" })
	void comparesDecodedEchoIncludingEmptyAndEscapedMessages(String message) throws Exception {
		String arguments = this.mapper.writeValueAsString(Map.of("message", message));
		String response = callbackResponse(this.mapper.writeValueAsString(Map.of("echo", message)));
		assertThatCode(() -> AgentCoreGatewayStarterIT.assertEchoResponse(response, arguments))
			.doesNotThrowAnyException();
	}

	@Test
	void ignoresExplanatoryTextBeforeJsonEcho() throws Exception {
		String response = this.mapper.writeValueAsString(List.of(Map.of("type", "text", "text", "Completed"),
				Map.of("type", "text", "text", "{\"echo\":\"hello\"}")));
		assertThatCode(() -> AgentCoreGatewayStarterIT.assertEchoResponse(response, "{\"message\":\"hello\"}"))
			.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"echo\":\"hello suffix\"}", "{\"message\":\"hello\"}", "private response text" })
	void rejectsIncorrectEchoWithoutExposingContent(String text) throws Exception {
		String response = callbackResponse(text);
		assertThatThrownBy(() -> AgentCoreGatewayStarterIT.assertEchoResponse(response, "{\"message\":\"hello\"}"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Gateway JSON echo must match")
			.hasMessageNotContaining(text)
			.hasNoCause();
	}

	@Test
	void emptyMessageDoesNotMatchMissingEcho() throws Exception {
		String response = callbackResponse("{\"ok\":true}");
		assertThatThrownBy(() -> AgentCoreGatewayStarterIT.assertEchoResponse(response, "{\"message\":\"\"}"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("Gateway JSON echo must match");
	}

	@ParameterizedTest
	@ValueSource(strings = { "private invalid JSON", "[]", "null", "{\"message\":42}" })
	void rejectsInvalidArgumentsWithoutExposingThem(String arguments) throws Exception {
		String response = callbackResponse("{\"echo\":\"hello\"}");
		assertThatThrownBy(() -> AgentCoreGatewayStarterIT.assertEchoResponse(response, arguments))
			.isInstanceOf(AssertionError.class)
			.hasMessageNotContaining(arguments)
			.hasNoCause();
	}

	@Test
	void rejectsMalformedCallbackContentWithoutExposingIt() {
		String response = "private invalid callback content";
		assertThatThrownBy(() -> AgentCoreGatewayStarterIT.assertEchoResponse(response, "{\"message\":\"hello\"}"))
			.isInstanceOf(AssertionError.class)
			.hasMessageNotContaining(response)
			.hasNoCause();
	}

	private String callbackResponse(String text) throws Exception {
		return this.mapper.writeValueAsString(List.of(Map.of("type", "text", "text", text)));
	}

	private String startServer() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			this.paths.add(exchange.getRequestURI().getPath());
			this.signatures.add(exchange.getRequestHeaders().containsKey("Authorization"));
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
			}
			else {
				Map<?, ?> request = this.mapper.readValue(body, Map.class);
				Object result = switch ((String) request.get("method")) {
					case "initialize" -> Map.of("protocolVersion", "2025-06-18", "capabilities",
							Map.of("tools", Map.of("listChanged", false)), "serverInfo",
							Map.of("name", "echo", "version", "1"));
					case "tools/list" -> Map.of("tools",
							List.of(Map.of("name", "echo", "description", "Echo a message", "inputSchema",
									Map.of("type", "object", "properties", Map.of("message", Map.of("type", "string")),
											"required", List.of("message")))));
					case "tools/call" -> {
						Map<?, ?> parameters = (Map<?, ?>) request.get("params");
						Map<?, ?> arguments = (Map<?, ?>) parameters.get("arguments");
						yield Map.of("isError", false, "content", List.of(Map.of("type", "text", "text",
								this.mapper.writeValueAsString(Map.of("echo", arguments.get("message"))))));
					}
					default -> null;
				};
				if (result == null) {
					exchange.sendResponseHeaders(202, -1);
				}
				else {
					byte[] response = this.mapper
						.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
					exchange.getResponseHeaders().add("Content-Type", "application/json");
					exchange.sendResponseHeaders(200, response.length);
					exchange.getResponseBody().write(response);
				}
			}
			exchange.close();
		});
		this.server.start();
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	@Configuration(proxyBeanMethods = false)
	static class TestCredentials {

		@Bean
		AwsCredentialsProvider credentials() {
			return () -> AwsBasicCredentials.create("test", "test-secret");
		}

	}

}
