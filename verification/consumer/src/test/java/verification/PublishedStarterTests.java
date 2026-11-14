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

package verification;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import tools.jackson.databind.ObjectMapper;

import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PublishedStarterTests {

	@Test
	@SuppressWarnings("unchecked")
	void publishedStarterDiscoversAutoConfigurationAndSignsActualMcpRequest() throws Exception {
		AtomicBoolean signed = new AtomicBoolean();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if (body.contains("\"initialize\"")) {
				String authorization = exchange.getRequestHeaders().getFirst("Authorization");
				signed
					.set(authorization != null && authorization.contains("/us-east-1/bedrock-agentcore/aws4_request"));
				Object id = new ObjectMapper().readValue(body, Map.class).get("id");
				byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":" + new ObjectMapper().writeValueAsString(id)
						+ ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
						+ "\"serverInfo\":{\"name\":\"consumer\",\"version\":\"1\"}}}")
					.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			}
			else {
				exchange.sendResponseHeaders("GET".equals(exchange.getRequestMethod()) ? 405 : 202, -1);
			}
			exchange.close();
		});
		server.start();
		try {
			try (var context = new SpringApplicationBuilder(ConsumerApplication.class).web(WebApplicationType.NONE)
				.properties(Map.of("spring.main.banner-mode", "off", "logging.level.root", "OFF",
						"spring.ai.mcp.client.initialized", "false", "spring.ai.mcp.client.toolcallback.enabled",
						"false", "spring.ai.mcp.client.streamable-http.connections.agentcore.url",
						"http://127.0.0.1:" + server.getAddress().getPort(), "mcp.sigv4.connections.agentcore.region",
						"us-east-1", "mcp.sigv4.connections.agentcore.allow-insecure-http", "true"))
				.run()) {
				List<NamedClientMcpTransport> transports = (List<NamedClientMcpTransport>) context
					.getBean("streamableHttpHttpClientTransports");
				assertThat(transports).hasSize(1);
				var client = McpClient.sync(transports.get(0).transport())
					.requestTimeout(Duration.ofSeconds(5))
					.build();
				try {
					client.initialize();
				}
				finally {
					client.closeGracefully();
				}
				assertThat(signed).as("published starter installs SigV4 via AutoConfiguration.imports").isTrue();
			}
		}
		finally {
			server.stop(0);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	static class ConsumerApplication {

		@Bean
		AwsCredentialsProvider credentials() {
			return () -> AwsBasicCredentials.create("test", "test-secret");
		}

	}

}
