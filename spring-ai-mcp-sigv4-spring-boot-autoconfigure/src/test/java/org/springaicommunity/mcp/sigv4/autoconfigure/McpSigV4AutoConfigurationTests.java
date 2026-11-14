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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springaicommunity.mcp.sigv4.RoutingAwsSigV4McpRequestCustomizer;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import tools.jackson.databind.ObjectMapper;

import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class McpSigV4AutoConfigurationTests {

	private static final String TRANSPORT = "spring.ai.mcp.client.streamable-http.connections.";

	private static final String AWS = McpAwsProperties.CONFIG_PREFIX + ".connections.";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(McpSigV4AutoConfiguration.class));

	private HttpServer server;

	private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

	@AfterEach
	void stopServer() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@Test
	void registersNamedTransportCustomizerWithoutGlobalSigner() {
		configured().run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(McpSigV4TransportCustomizer.class);
			assertThat(context).hasSingleBean(McpClientCustomizer.class);
			assertThat(context).doesNotHaveBean(McpAsyncHttpClientRequestCustomizer.class);
			assertThat(context).doesNotHaveBean(RoutingAwsSigV4McpRequestCustomizer.class);
		});
	}

	@Test
	void registersDefaultAwsProvidersWhenSigningIsConfigured() {
		this.runner
			.withPropertyValues(TRANSPORT + "agentcore.url=https://gateway.example", AWS + "agentcore.region=us-east-1")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(DefaultCredentialsProvider.class);
				assertThat(context).hasSingleBean(DefaultAwsRegionProviderChain.class);
			});
	}

	@Test
	void reusesApplicationProviders() {
		AwsCredentialsProvider credentials = credentialsProvider();
		AwsRegionProvider region = () -> software.amazon.awssdk.regions.Region.US_EAST_1;
		this.runner
			.withPropertyValues(TRANSPORT + "agentcore.url=https://gateway.example",
					AWS + "agentcore.service-name=bedrock-agentcore")
			.withBean(AwsCredentialsProvider.class, () -> credentials)
			.withBean(AwsRegionProvider.class, () -> region)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AwsCredentialsProvider.class)).isSameAs(credentials);
				assertThat(context.getBean(AwsRegionProvider.class)).isSameAs(region);
				assertThat(context).doesNotHaveBean(DefaultCredentialsProvider.class);
				assertThat(context).doesNotHaveBean(DefaultAwsRegionProviderChain.class);
			});
	}

	@Test
	void doesNotRegisterWithoutAwsConnections() {
		this.runner.withPropertyValues(TRANSPORT + "public.url=https://public.example").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(McpSigV4TransportCustomizer.class);
			assertThat(context).doesNotHaveBean(AwsCredentialsProvider.class);
			assertThat(context).doesNotHaveBean(AwsRegionProvider.class);
			assertThat(context).doesNotHaveBean(org.springaicommunity.mcp.sigv4.AwsSigV4HeaderSigningPolicy.class);
		});
	}

	@Test
	void doesNotRegisterWhenDisabled() {
		configured().withPropertyValues("spring.ai.mcp.client.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(McpSigV4TransportCustomizer.class);
		});
	}

	@Test
	void failsForUnknownConnection() {
		configured().withPropertyValues(AWS + "typo.region=us-east-1").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasRootCauseMessage("mcp.sigv4 connection 'typo' has no matching streamable-http connection");
		});
	}

	@Test
	void rejectsInsecureHttp() {
		configured().withPropertyValues(TRANSPORT + "agentcore.url=http://localhost:8080").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseMessage(
					"refusing to send SigV4 credentials over insecure HTTP for connection 'agentcore'; "
							+ "set allow-insecure-http=true only for trusted local tests");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://private.example/invalid path", "https:///mcp",
			"https://user:password@private.example/mcp", "https://private.example/mcp#fragment" })
	void rejectsInvalidEndpointsWithoutExposingTheirValues(String url) {
		configured().withPropertyValues(TRANSPORT + "agentcore.url=" + url).run(context -> {
			assertThat(context).hasFailed();
			var trace = new StringWriter();
			context.getStartupFailure().printStackTrace(new PrintWriter(trace));
			assertThat(trace.toString()).doesNotContain("private.example", "password");
		});
	}

	@Test
	void rejectsAmbiguousCredentialsProviders() {
		configured()
			.withBean("otherProvider", AwsCredentialsProvider.class,
					McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class);
			});
	}

	@Test
	void rejectsLegacyConfigurationInsteadOfSilentlyDisablingSigning() {
		configured().withPropertyValues("spring.ai.mcp.client.authorization.aws.connections.agentcore.region=us-east-1")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseMessage(
						"Move AWS signing settings from spring.ai.mcp.client.authorization.aws.connections to mcp.sigv4.connections");
			});
	}

	@Test
	void keepsNamedScopesIndependentAtTheSameEndpoint() throws Exception {
		String url = startServer(false);
		wireRunner()
			.withPropertyValues(TRANSPORT + "first.url=" + url, TRANSPORT + "second.url=" + url,
					AWS + "first.region=us-east-1", AWS + "first.allow-insecure-http=true",
					AWS + "second.region=ap-northeast-2", AWS + "second.allow-insecure-http=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				initializeConnections(context);
			});
		assertThat(this.requests).anySatisfy(request -> assertThat(request.header("Authorization"))
			.contains("/us-east-1/bedrock-agentcore/aws4_request"));
		assertThat(this.requests).anySatisfy(request -> assertThat(request.header("Authorization"))
			.contains("/ap-northeast-2/bedrock-agentcore/aws4_request"));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void signsAfterNativeSyncAsyncAndNamedTransportCustomizersOnTheWire(boolean async) throws Exception {
		String url = startServer(true);
		wireRunner().withUserConfiguration(HeaderConfiguration.class)
			.withPropertyValues(TRANSPORT + "agentcore.url=" + url, AWS + "agentcore.region=us-east-1",
					AWS + "agentcore.allow-insecure-http=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				initializeConnections(context, async);
			});
		assertThat(this.requests).anySatisfy(request -> {
			assertThat(request.method()).isEqualTo("POST");
			assertThat(request.header("X-Sync")).isEqualTo("sync");
			assertThat(request.header("X-Async")).isEqualTo("async");
			assertThat(request.header("X-Direct")).isEqualTo("direct");
			assertThat(request.header("Authorization")).contains("x-sync", "x-async", "x-direct");
		});
		assertThat(this.requests).anySatisfy(request -> {
			assertThat(request.method()).isEqualTo("DELETE");
			assertThat(request.header("Mcp-Session-Id")).isEqualTo("test-session");
			assertThat(request.header("Authorization")).contains("mcp-session-id");
		});
		assertThat(this.requests).allSatisfy(request -> assertThat(request.header("Authorization")).isNotNull());
	}

	@Test
	void unsignedAliasOfSignedEndpointRemainsUnsignedOnTheWire() throws Exception {
		String url = startServer(false);
		AtomicInteger resolutions = new AtomicInteger();
		wireRunner(() -> {
			resolutions.incrementAndGet();
			return AwsBasicCredentials.create("test", "test-secret");
		}).withPropertyValues(TRANSPORT + "signed.url=" + url, TRANSPORT + "public-alias.url=" + url,
				AWS + "signed.region=us-east-1", AWS + "signed.allow-insecure-http=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				initializeConnections(context);
			});
		long signed = this.requests.stream().filter(request -> request.header("Authorization") != null).count();
		long unsigned = this.requests.stream().filter(request -> request.header("Authorization") == null).count();
		assertThat(signed).isPositive();
		assertThat(unsigned).isPositive();
		assertThat(resolutions).hasValue((int) signed);
	}

	@Test
	void signsEachConnectionWithItsScopeAndOneSharedProvider() throws Exception {
		String url = startServer(false);
		wireRunner().withPropertyValues(TRANSPORT + "first.url=" + url, TRANSPORT + "first.endpoint=/first",
				TRANSPORT + "second.url=" + url, TRANSPORT + "second.endpoint=/second", TRANSPORT + "public.url=" + url,
				TRANSPORT + "public.endpoint=/public", AWS + "first.region=us-east-1",
				AWS + "first.allow-insecure-http=true", AWS + "second.region=ap-northeast-2",
				AWS + "second.service-name=execute-api", AWS + "second.allow-insecure-http=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				initializeConnections(context);
			});
		assertThat(this.requests).filteredOn(request -> request.path().equals("/first"))
			.isNotEmpty()
			.allSatisfy(request -> assertThat(request.header("Authorization"))
				.contains("/us-east-1/bedrock-agentcore/aws4_request"));
		assertThat(this.requests).filteredOn(request -> request.path().equals("/second"))
			.isNotEmpty()
			.allSatisfy(request -> assertThat(request.header("Authorization"))
				.contains("/ap-northeast-2/execute-api/aws4_request"));
		assertThat(this.requests).filteredOn(request -> request.path().equals("/public"))
			.isNotEmpty()
			.allSatisfy(request -> assertThat(request.header("Authorization")).isNull());
	}

	private ApplicationContextRunner configured() {
		return this.runner
			.withPropertyValues(TRANSPORT + "agentcore.url=https://gateway.example", AWS + "agentcore.region=us-east-1")
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider);
	}

	private ApplicationContextRunner wireRunner() {
		return wireRunner(credentialsProvider());
	}

	private ApplicationContextRunner wireRunner(AwsCredentialsProvider credentials) {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(McpSigV4AutoConfiguration.class,
					StreamableHttpHttpClientTransportAutoConfiguration.class))
			.withBean(AwsCredentialsProvider.class, () -> credentials);
	}

	private static AwsCredentialsProvider credentialsProvider() {
		return () -> AwsBasicCredentials.create("test", "test-secret");
	}

	private static void initializeConnections(ApplicationContext context) {
		initializeConnections(context, false);
	}

	@SuppressWarnings("unchecked")
	private static void initializeConnections(ApplicationContext context, boolean async) {
		List<NamedClientMcpTransport> transports = (List<NamedClientMcpTransport>) context
			.getBean("streamableHttpHttpClientTransports");
		for (NamedClientMcpTransport transport : transports) {
			if (async) {
				var asyncClient = McpClient.async(transport.transport()).requestTimeout(Duration.ofSeconds(5)).build();
				try {
					asyncClient.initialize().block(Duration.ofSeconds(5));
				}
				finally {
					asyncClient.closeGracefully().block(Duration.ofSeconds(5));
				}
				continue;
			}
			var client = McpClient.sync(transport.transport()).requestTimeout(Duration.ofSeconds(5)).build();
			try {
				client.initialize();
			}
			finally {
				client.closeGracefully();
			}
		}
	}

	private String startServer(boolean stateful) throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			this.requests.add(new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					Map.copyOf(exchange.getRequestHeaders())));
			if ("POST".equals(exchange.getRequestMethod()) && body.contains("\"initialize\"")) {
				Object id = new ObjectMapper().readValue(body, Map.class).get("id");
				byte[] response = ("{\"jsonrpc\":\"2.0\",\"id\":" + new ObjectMapper().writeValueAsString(id)
						+ ",\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
						+ "\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}}")
					.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				if (stateful) {
					exchange.getResponseHeaders().add("Mcp-Session-Id", "test-session");
				}
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			}
			else {
				exchange.sendResponseHeaders("GET".equals(exchange.getRequestMethod()) ? 405 : 202, -1);
			}
			exchange.close();
		});
		this.server.start();
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	@Configuration(proxyBeanMethods = false)
	static class HeaderConfiguration {

		@Bean
		McpSyncHttpClientRequestCustomizer syncCustomizer() {
			return (builder, method, endpoint, body, context) -> builder.header("X-Sync", "sync");
		}

		@Bean
		McpAsyncHttpClientRequestCustomizer asyncCustomizer() {
			return (builder, method, endpoint, body, context) -> Mono.just(builder.header("X-Async", "async"));
		}

		@Bean
		McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> transportCustomizer() {
			return (name, builder) -> builder.addHttpRequestCustomizer(
					(request, method, endpoint, body, context) -> request.header("X-Direct", "direct"));
		}

	}

	private record CapturedRequest(String method, String path, Map<String, List<String>> headers) {
		String header(String name) {
			return this.headers.entrySet()
				.stream()
				.filter(entry -> entry.getKey().equalsIgnoreCase(name))
				.map(entry -> entry.getValue().get(0))
				.findFirst()
				.orElse(null);
		}
	}

}
