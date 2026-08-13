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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.DelegatingMcpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class McpSigV4AutoConfigurationTests {

	private static final String TRANSPORT = "spring.ai.mcp.client.streamable-http.connections.agentcore.url=https://gw.example.com/base/";

	private static final String ENDPOINT = "spring.ai.mcp.client.streamable-http.connections.agentcore.endpoint=mcp";

	private static final String AWS_REGION = "spring.ai.mcp.client.authorization.aws.connections.agentcore.region=ap-northeast-2";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(McpSigV4AutoConfiguration.class));

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@Test
	void registersConnectionScopedRequestCustomizer() {
		this.contextRunner.withPropertyValues(TRANSPORT, ENDPOINT, AWS_REGION)
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(RoutingAwsSigV4McpRequestCustomizer.class);
				assertThat(context).hasSingleBean(McpAsyncHttpClientRequestCustomizer.class);
				RoutingAwsSigV4McpRequestCustomizer customizer = context
					.getBean(RoutingAwsSigV4McpRequestCustomizer.class);
				assertThat(customizer.supports(URI.create("https://gw.example.com/base/mcp"))).isTrue();
				assertThat(customizer.supports(URI.create("https://public.example.com/mcp"))).isFalse();
			});
	}

	@Test
	void registersDefaultAwsProvidersWhenSigningIsConfigured() {
		this.contextRunner.withPropertyValues(TRANSPORT, ENDPOINT, AWS_REGION).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AwsCredentialsProvider.class);
			assertThat(context).hasSingleBean(DefaultCredentialsProvider.class);
			assertThat(context).hasSingleBean(AwsRegionProvider.class);
			assertThat(context).hasSingleBean(DefaultAwsRegionProviderChain.class);
		});
	}

	@Test
	void coexistsWithApplicationRequestCustomizers() {
		this.contextRunner.withUserConfiguration(ApplicationCustomizerConfiguration.class)
			.withPropertyValues(TRANSPORT, ENDPOINT, AWS_REGION)
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeansOfType(McpAsyncHttpClientRequestCustomizer.class)).hasSize(2);
				assertThat(context).hasSingleBean(RoutingAwsSigV4McpRequestCustomizer.class);
			});
	}

	@Test
	void isAppliedBySpringAiHttpClientTransportAutoConfiguration() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(McpSigV4AutoConfiguration.class,
					StreamableHttpHttpClientTransportAutoConfiguration.class))
			.withPropertyValues(TRANSPORT, ENDPOINT, AWS_REGION)
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasNotFailed();
				@SuppressWarnings("unchecked")
				List<NamedClientMcpTransport> transports = (List<NamedClientMcpTransport>) context
					.getBean("streamableHttpHttpClientTransports");
				assertThat(transports).singleElement().satisfies(named -> {
					assertThat(named.name()).isEqualTo("agentcore");
					HttpClientStreamableHttpTransport transport = (HttpClientStreamableHttpTransport) named.transport();
					McpAsyncHttpClientRequestCustomizer installed = (McpAsyncHttpClientRequestCustomizer) ReflectionTestUtils
						.getField(transport, "httpRequestCustomizer");
					assertThat(installed).isInstanceOf(RoutingAwsSigV4McpRequestCustomizer.class);
					assertThat(authorization(installed, URI.create("https://gw.example.com/base/mcp")))
						.contains("/ap-northeast-2/bedrock-agentcore/aws4_request");
				});
			});
	}

	@Test
	void legacyBridgeComposesSyncAndAsyncCustomizersBeforeSigV4() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(McpSigV4AutoConfiguration.class,
					StreamableHttpHttpClientTransportAutoConfiguration.class))
			.withUserConfiguration(SyncAndAsyncCustomizerConfiguration.class)
			.withPropertyValues(TRANSPORT, ENDPOINT, AWS_REGION)
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasNotFailed();
				@SuppressWarnings("unchecked")
				List<NamedClientMcpTransport> transports = (List<NamedClientMcpTransport>) context
					.getBean("streamableHttpHttpClientTransports");
				HttpClientStreamableHttpTransport transport = (HttpClientStreamableHttpTransport) transports.get(0)
					.transport();
				McpAsyncHttpClientRequestCustomizer installed = (McpAsyncHttpClientRequestCustomizer) ReflectionTestUtils
					.getField(transport, "httpRequestCustomizer");
				assertThat(installed).isInstanceOf(DelegatingMcpAsyncHttpClientRequestCustomizer.class);

				HttpRequest request = customizedRequest(installed, URI.create("https://gw.example.com/base/mcp"));
				assertThat(request.headers().firstValue("X-Sync")).contains("sync");
				assertThat(request.headers().firstValue("X-Async")).contains("async");
				assertThat(request.headers().allValues("X-Order")).containsExactly("sync", "async");
				assertThat(request.headers().firstValue("Authorization")).hasValueSatisfying(
						value -> assertThat(value).contains("x-sync").contains("x-async").contains("x-order"));
			});
	}

	@Test
	void legacyBridgeComposesSyncOnlyCustomizerBeforeSigV4() {
		runWithTransport(SyncOnlyCustomizerConfiguration.class, installed -> {
			assertThat(installed).isInstanceOf(DelegatingMcpAsyncHttpClientRequestCustomizer.class);
			HttpRequest request = customizedRequest(installed, URI.create("https://gw.example.com/base/mcp"));
			assertThat(request.headers().firstValue("X-Sync")).contains("sync");
			assertThat(request.headers().firstValue("Authorization"))
				.hasValueSatisfying(value -> assertThat(value).contains("x-sync"));
		});
	}

	@Test
	void legacyBridgeComposesAsyncOnlyCustomizerBeforeSigV4() {
		runWithTransport(AsyncOnlyCustomizerConfiguration.class, installed -> {
			assertThat(installed).isInstanceOf(DelegatingMcpAsyncHttpClientRequestCustomizer.class);
			HttpRequest request = customizedRequest(installed, URI.create("https://gw.example.com/base/mcp"));
			assertThat(request.headers().firstValue("X-Async")).contains("async");
			assertThat(request.headers().firstValue("Authorization"))
				.hasValueSatisfying(value -> assertThat(value).contains("x-async"));
		});
	}

	@Test
	void directTransportCustomizerCannotBeDiscoveredForComposition() {
		runWithTransport(DirectTransportCustomizerConfiguration.class, installed -> {
			HttpRequest request = customizedRequest(installed, URI.create("https://gw.example.com/base/mcp"));
			assertThat(request.headers().firstValue("X-Direct")).isEmpty();
			assertThat(request.headers().firstValue("Authorization")).isPresent();
		});
	}

	@Test
	void syncClientExecutesComposedCustomizerPipelineEndToEnd() throws Exception {
		AtomicReference<CapturedRequest> initializedRequest = new AtomicReference<>();
		this.server = HttpServer.create(new InetSocketAddress(0), 0);
		this.server.createContext("/mcp", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if ("POST".equals(exchange.getRequestMethod()) && body.contains("\"initialize\"")) {
				initializedRequest.set(new CapturedRequest(exchange.getRequestHeaders().getFirst("X-Sync"),
						exchange.getRequestHeaders().getFirst("X-Async"),
						exchange.getRequestHeaders().getFirst("Authorization")));
				Object requestId = new ObjectMapper().readValue(body, Map.class).get("id");
				String id = requestId instanceof String ? "\"" + requestId + "\"" : requestId.toString();
				byte[] response = ("""
						{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"pipeline-test","version":"1.0.0"}}}
						""")
					.formatted(id)
					.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.getResponseHeaders().add("Mcp-Session-Id", "pipeline-session");
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			}
			else if ("GET".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
			}
			else {
				exchange.sendResponseHeaders(202, -1);
			}
			exchange.close();
		});
		this.server.start();

		String url = "http://localhost:" + this.server.getAddress().getPort();
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(McpSigV4AutoConfiguration.class,
					StreamableHttpHttpClientTransportAutoConfiguration.class))
			.withUserConfiguration(SyncAndAsyncCustomizerConfiguration.class)
			.withPropertyValues("spring.ai.mcp.client.streamable-http.connections.agentcore.url=" + url, AWS_REGION,
					"spring.ai.mcp.client.authorization.aws.connections.agentcore.allow-insecure-http=true")
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasNotFailed();
				@SuppressWarnings("unchecked")
				List<NamedClientMcpTransport> transports = (List<NamedClientMcpTransport>) context
					.getBean("streamableHttpHttpClientTransports");
				var client = McpClient.sync(transports.get(0).transport()).build();
				client.initialize();
				client.closeGracefully();
			});

		assertThat(initializedRequest.get()).satisfies(request -> {
			assertThat(request.syncHeader()).isEqualTo("sync");
			assertThat(request.asyncHeader()).isEqualTo("async");
			assertThat(request.authorization()).contains("x-sync").contains("x-async").contains("x-order");
		});
	}

	@Test
	void supportsDifferentSigningScopesForDifferentConnections() {
		this.contextRunner
			.withPropertyValues(TRANSPORT, ENDPOINT, AWS_REGION,
					"spring.ai.mcp.client.streamable-http.connections.other.url=https://other.example.com",
					"spring.ai.mcp.client.authorization.aws.connections.other.region=us-east-1",
					"spring.ai.mcp.client.authorization.aws.connections.other.service-name=execute-api")
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasNotFailed();
				RoutingAwsSigV4McpRequestCustomizer customizer = context
					.getBean(RoutingAwsSigV4McpRequestCustomizer.class);
				assertThat(authorization(customizer, URI.create("https://gw.example.com/base/mcp")))
					.contains("/ap-northeast-2/bedrock-agentcore/aws4_request");
				assertThat(authorization(customizer, URI.create("https://other.example.com/mcp")))
					.contains("/us-east-1/execute-api/aws4_request");
			});
	}

	@Test
	void doesNotRegisterWhenNoAwsConnectionExists() {
		this.contextRunner.withPropertyValues(TRANSPORT).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(RoutingAwsSigV4McpRequestCustomizer.class);
			assertThat(context).doesNotHaveBean(AwsCredentialsProvider.class);
			assertThat(context).doesNotHaveBean(AwsRegionProvider.class);
		});
	}

	@Test
	void doesNotRegisterWhenMcpClientIsDisabled() {
		this.contextRunner.withPropertyValues("spring.ai.mcp.client.enabled=false", TRANSPORT, AWS_REGION)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(RoutingAwsSigV4McpRequestCustomizer.class);
			});
	}

	@Test
	void failsForUnknownConnectionName() {
		this.contextRunner
			.withPropertyValues(TRANSPORT,
					"spring.ai.mcp.client.authorization.aws.connections.typo.region=ap-northeast-2")
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseMessage(
						"authorization.aws connection 'typo' has no matching streamable-http connection");
			});
	}

	@Test
	void rejectsInsecureHttpByDefault() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.client.streamable-http.connections.agentcore.url=http://localhost:8080",
					AWS_REGION)
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseMessage(
						"refusing to send SigV4 credentials over insecure HTTP for connection 'agentcore'; "
								+ "set allow-insecure-http=true only for trusted local tests");
			});
	}

	@Test
	void allowsInsecureHttpOnlyWhenExplicitlyConfigured() {
		this.contextRunner
			.withPropertyValues("spring.ai.mcp.client.streamable-http.connections.agentcore.url=http://localhost:8080",
					AWS_REGION, "spring.ai.mcp.client.authorization.aws.connections.agentcore.allow-insecure-http=true")
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(RoutingAwsSigV4McpRequestCustomizer.class)
					.supports(URI.create("http://localhost:8080/mcp"))).isTrue();
			});
	}

	@Test
	void rejectsMultipleCredentialsProviderBeans() {
		this.contextRunner.withPropertyValues(TRANSPORT, ENDPOINT, AWS_REGION)
			.withBean("firstCredentialsProvider", AwsCredentialsProvider.class,
					McpSigV4AutoConfigurationTests::credentialsProvider)
			.withBean("secondCredentialsProvider", AwsCredentialsProvider.class,
					McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseMessage(
						"No qualifying bean of type 'software.amazon.awssdk.auth.credentials.AwsCredentialsProvider' "
								+ "available: expected single matching bean but found 2: "
								+ "firstCredentialsProvider,secondCredentialsProvider");
			});
	}

	@Test
	void rejectsConflictingScopesForTheSameResolvedEndpoint() {
		this.contextRunner
			.withPropertyValues(TRANSPORT, ENDPOINT, AWS_REGION,
					"spring.ai.mcp.client.streamable-http.connections.alias.url=https://gw.example.com/base/",
					"spring.ai.mcp.client.streamable-http.connections.alias.endpoint=mcp",
					"spring.ai.mcp.client.authorization.aws.connections.alias.region=us-east-1")
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseMessage(
						"MCP connections resolving to the same endpoint have conflicting AWS signing scopes");
				assertThat(context.getStartupFailure()).hasMessageNotContaining("gw.example.com");
			});
	}

	private static AwsCredentialsProvider credentialsProvider() {
		return () -> AwsBasicCredentials.create("AKID", "secret");
	}

	private static void runWithTransport(Class<?> userConfiguration,
			java.util.function.Consumer<McpAsyncHttpClientRequestCustomizer> assertions) {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(McpSigV4AutoConfiguration.class,
					StreamableHttpHttpClientTransportAutoConfiguration.class))
			.withUserConfiguration(userConfiguration)
			.withPropertyValues(TRANSPORT, ENDPOINT, AWS_REGION)
			.withBean(AwsCredentialsProvider.class, McpSigV4AutoConfigurationTests::credentialsProvider)
			.run(context -> {
				assertThat(context).hasNotFailed();
				@SuppressWarnings("unchecked")
				List<NamedClientMcpTransport> transports = (List<NamedClientMcpTransport>) context
					.getBean("streamableHttpHttpClientTransports");
				HttpClientStreamableHttpTransport transport = (HttpClientStreamableHttpTransport) transports.get(0)
					.transport();
				McpAsyncHttpClientRequestCustomizer installed = (McpAsyncHttpClientRequestCustomizer) ReflectionTestUtils
					.getField(transport, "httpRequestCustomizer");
				assertions.accept(installed);
			});
	}

	private static String authorization(RoutingAwsSigV4McpRequestCustomizer customizer, URI endpoint) {
		return authorization((McpAsyncHttpClientRequestCustomizer) customizer, endpoint);
	}

	private static String authorization(McpAsyncHttpClientRequestCustomizer customizer, URI endpoint) {
		return customizedRequest(customizer, endpoint).headers().firstValue("Authorization").orElseThrow();
	}

	private static HttpRequest customizedRequest(McpAsyncHttpClientRequestCustomizer customizer, URI endpoint) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).GET();
		return Mono.from(customizer.customize(builder, "GET", endpoint, null, McpTransportContext.EMPTY))
			.block()
			.build();
	}

	@Configuration(proxyBeanMethods = false)
	static class ApplicationCustomizerConfiguration {

		@Bean
		McpAsyncHttpClientRequestCustomizer applicationCustomizer() {
			return (builder, method, endpoint, body, context) -> Mono.just(builder.header("X-App", "test"));
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class SyncAndAsyncCustomizerConfiguration {

		@Bean
		McpSyncHttpClientRequestCustomizer syncRequestCustomizer() {
			return (builder, method, endpoint, body, context) -> builder.header("X-Sync", "sync")
				.header("X-Order", "sync");
		}

		@Bean
		McpAsyncHttpClientRequestCustomizer asyncRequestCustomizer() {
			return (builder, method, endpoint, body, context) -> Mono
				.just(builder.header("X-Async", "async").header("X-Order", "async"));
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class SyncOnlyCustomizerConfiguration {

		@Bean
		McpSyncHttpClientRequestCustomizer syncRequestCustomizer() {
			return (builder, method, endpoint, body, context) -> builder.header("X-Sync", "sync");
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class AsyncOnlyCustomizerConfiguration {

		@Bean
		McpAsyncHttpClientRequestCustomizer asyncRequestCustomizer() {
			return (builder, method, endpoint, body, context) -> Mono.just(builder.header("X-Async", "async"));
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class DirectTransportCustomizerConfiguration {

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> directTransportCustomizer() {
			return (name, builder) -> builder.asyncHttpRequestCustomizer(
					(request, method, endpoint, body, context) -> Mono.just(request.header("X-Direct", "direct")));
		}

	}

	private record CapturedRequest(String syncHeader, String asyncHeader, String authorization) {
	}

}
