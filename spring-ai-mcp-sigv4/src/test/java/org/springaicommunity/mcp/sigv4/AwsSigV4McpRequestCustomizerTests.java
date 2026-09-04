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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AwsSigV4McpRequestCustomizerTests {

	private static final AwsCredentials BASIC_CREDENTIALS = AwsBasicCredentials.create("AKIDEXAMPLE",
			"test-secret-key-value");

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@Test
	void shouldSignGetPostAndDeleteRequests() {
		for (String method : List.of("GET", "POST", "DELETE")) {
			HttpRequest request = sign(method, URI.create("https://example.com/path/mcp?x=a%20b"),
					"POST".equals(method) ? "{\"value\":1}" : null, () -> BASIC_CREDENTIALS);

			assertThat(request.headers().firstValue("Authorization"))
				.hasValueSatisfying(value -> assertThat(value).startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/")
					.contains("/ap-northeast-2/bedrock-agentcore/aws4_request"));
			assertThat(request.headers().firstValue("X-Amz-Date")).isPresent();
		}
	}

	@Test
	void shouldSignNullEmptyJsonAndUtf8Bodies() {
		URI uri = URI.create("https://example.com/mcp/");
		HttpRequest nullBody = sign("POST", uri, null, () -> BASIC_CREDENTIALS);
		HttpRequest emptyBody = sign("POST", uri, "", () -> BASIC_CREDENTIALS);
		HttpRequest jsonBody = sign("POST", uri, "{\"message\":\"hello\"}", () -> BASIC_CREDENTIALS);
		HttpRequest utf8Body = sign("POST", uri, "{\"message\":\"\uC548\uB155\uD558\uC138\uC694\"}",
				() -> BASIC_CREDENTIALS);

		assertThat(authorization(nullBody)).isNotBlank();
		assertThat(authorization(emptyBody)).isNotBlank();
		assertThat(authorization(jsonBody)).isNotEqualTo(authorization(utf8Body));
		assertThat(authorization(jsonBody)).isNotEqualTo(authorization(emptyBody));
		assertThat(jsonBody.headers().firstValue("X-Amz-Content-Sha256")).isPresent();
		assertThat(utf8Body.headers().firstValue("X-Amz-Content-Sha256")).isPresent();
	}

	@Test
	void shouldLeavePropagationHeadersOnWireWithoutSigningThem() {
		URI endpoint = URI.create("https://example.com/mcp");
		HttpRequest.Builder builder = requestBuilder("POST", endpoint, "{}")
			.header("TraceParent", "parent-context")
			.header("TraceState", "test=value")
			.header("Baggage", "test=value")
			.header("X-Custom-Header", "stable");
		HttpRequest request = customize(builder, "POST", endpoint, "{}", () -> BASIC_CREDENTIALS);
		assertThat(request.headers().firstValue("traceparent")).contains("parent-context");
		assertThat(request.headers().firstValue("tracestate")).contains("test=value");
		assertThat(request.headers().firstValue("baggage")).contains("test=value");
		String signedHeaders = authorization(request).split("SignedHeaders=", 2)[1].split(",", 2)[0];
		assertThat(signedHeaders).doesNotContain("traceparent", "tracestate", "baggage");
		assertThat(signedHeaders).contains("x-custom-header");
	}

	@Test
	void shouldPreserveMcpHeadersWhenSigning() {
		URI endpoint = URI.create("https://example.com/mcp");
		HttpRequest.Builder builder = requestBuilder("POST", endpoint, "{}").header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.header("Mcp-Protocol-Version", "2025-06-18")
			.header("Mcp-Session-Id", "safe-session-id")
			.header("Last-Event-Id", "event-1")
			.header("X-Custom-Header", "custom-value");

		HttpRequest request = customize(builder, "POST", endpoint, "{}", () -> BASIC_CREDENTIALS);

		assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
		assertThat(request.headers().firstValue("Accept")).contains("application/json");
		assertThat(request.headers().firstValue("Mcp-Protocol-Version")).contains("2025-06-18");
		assertThat(request.headers().firstValue("Mcp-Session-Id")).contains("safe-session-id");
		assertThat(request.headers().firstValue("Last-Event-Id")).contains("event-1");
		assertThat(request.headers().firstValue("X-Custom-Header")).contains("custom-value");
		assertThat(request.headers().allValues("Authorization")).hasSize(1);
		assertThat(authorization(request)).contains("accept", "content-type", "last-event-id", "mcp-protocol-version",
				"mcp-session-id", "x-custom-header");
	}

	@ParameterizedTest
	@ValueSource(strings = { "Authorization", "X-Amz-Date", "X-Amz-Security-Token", "X-Amz-Content-Sha256" })
	void shouldRejectPreexistingSigV4OwnedHeader(String headerName) {
		URI endpoint = URI.create("https://example.com/mcp");
		HttpRequest.Builder builder = requestBuilder("POST", endpoint, "{}").header(headerName, "sensitive-value");
		AtomicInteger credentialResolutions = new AtomicInteger();
		AwsCredentialsProvider provider = () -> {
			credentialResolutions.incrementAndGet();
			return BASIC_CREDENTIALS;
		};

		assertThatThrownBy(() -> customize(builder, "POST", endpoint, "{}", provider))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("AWS SigV4-owned headers must not be set before signing")
			.hasMessageNotContaining("sensitive-value")
			.hasMessageNotContaining("AKIDEXAMPLE")
			.hasMessageNotContaining("test-secret-key-value");
		assertThat(credentialResolutions).hasValue(0);
	}

	@Test
	void shouldRejectSigV4OwnedHeaderCaseInsensitively() {
		URI endpoint = URI.create("https://example.com/mcp");
		HttpRequest.Builder builder = requestBuilder("GET", endpoint, null).header("x-AmZ-sEcUrItY-ToKeN",
				"stale-token");

		assertThatThrownBy(() -> customize(builder, "GET", endpoint, null, () -> BASIC_CREDENTIALS))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("AWS SigV4-owned headers must not be set before signing")
			.hasMessageNotContaining("stale-token");
	}

	@Test
	void shouldIncludeSessionToken() {
		AwsSessionCredentials credentials = AwsSessionCredentials.create("SESSIONKEY", "session-secret",
				"session-token-value");
		URI uri = URI.create("https://example.com/mcp");
		var customizer = new AwsSigV4McpRequestCustomizer(() -> credentials, Region.AP_NORTHEAST_2, "bedrock-agentcore",
				uri);

		HttpRequest request = Mono
			.from(customizer.customize(requestBuilder("GET", uri, null), "GET", uri, null, McpTransportContext.EMPTY))
			.block()
			.build();

		assertThat(request.headers().firstValue("X-Amz-Security-Token")).contains("session-token-value");
	}

	@Test
	void shouldResolveCredentialsForEveryRequest() {
		AtomicInteger calls = new AtomicInteger();
		AwsCredentialsProvider rotatingProvider = () -> calls.getAndIncrement() == 0
				? AwsBasicCredentials.create("FIRSTKEY", "first-secret")
				: AwsBasicCredentials.create("SECONDKEY", "second-secret");

		HttpRequest first = sign("GET", URI.create("https://example.com/mcp"), null, rotatingProvider);
		HttpRequest second = sign("GET", URI.create("https://example.com/mcp"), null, rotatingProvider);

		assertThat(authorization(first)).contains("Credential=FIRSTKEY/");
		assertThat(authorization(second)).contains("Credential=SECONDKEY/").isNotEqualTo(authorization(first));
		assertThat(calls).hasValue(2);
	}

	@Test
	void shouldResolveCredentialsOnBoundedElasticScheduler() {
		AtomicReference<String> resolverThread = new AtomicReference<>();
		AwsCredentialsProvider provider = () -> {
			resolverThread.set(Thread.currentThread().getName());
			return BASIC_CREDENTIALS;
		};
		URI uri = URI.create("https://example.com/mcp");
		var customizer = new AwsSigV4McpRequestCustomizer(provider, Region.AP_NORTHEAST_2, "bedrock-agentcore", uri);
		Mono<HttpRequest.Builder> customized = Mono
			.from(customizer.customize(requestBuilder("GET", uri, null), "GET", uri, null, McpTransportContext.EMPTY))
			.subscribeOn(Schedulers.parallel());

		StepVerifier.create(customized).assertNext(builder -> assertThat(builder.build()).isNotNull()).verifyComplete();
		assertThat(resolverThread.get()).contains("boundedElastic");
	}

	@Test
	void shouldPropagateCredentialProviderFailure() {
		RuntimeException failure = new IllegalStateException("credentials unavailable");
		AwsCredentialsProvider provider = () -> {
			throw failure;
		};

		assertThatThrownBy(() -> sign("GET", URI.create("https://example.com/mcp"), null, provider)).isSameAs(failure);
	}

	@Test
	void shouldChangeSignatureWhenBodyChanges() {
		URI uri = URI.create("https://example.com/gateways/id/mcp?mode=one");
		HttpRequest first = sign("POST", uri, "{\"id\":1}", () -> BASIC_CREDENTIALS);
		HttpRequest changedBody = sign("POST", uri, "{\"id\":2}", () -> BASIC_CREDENTIALS);

		assertThat(authorization(changedBody)).isNotEqualTo(authorization(first));
	}

	@Test
	void shouldDeliverSignedHeadersAndOriginalBodyToLocalServer() throws Exception {
		List<CapturedRequest> captured = new CopyOnWriteArrayList<>();
		this.server = HttpServer.create(new InetSocketAddress(0), 0);
		this.server.createContext("/mcp", exchange -> {
			captured.add(new CapturedRequest(exchange.getRequestMethod(),
					exchange.getRequestHeaders().getFirst("Authorization"),
					exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
					new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		this.server.start();
		URI uri = URI.create("http://localhost:" + this.server.getAddress().getPort() + "/mcp");
		HttpClient client = HttpClient.newHttpClient();

		for (String method : List.of("GET", "POST", "DELETE")) {
			String body = "POST".equals(method) ? "{\"message\":\"\uC548\uB155\"}" : null;
			HttpRequest.Builder builder = requestBuilder(method, uri, body).header("Mcp-Session-Id", "session-1");
			HttpRequest request = customize(builder, method, uri, body, () -> BASIC_CREDENTIALS);
			client.send(request, HttpResponse.BodyHandlers.discarding());
		}

		assertThat(captured).extracting(CapturedRequest::method).containsExactly("GET", "POST", "DELETE");
		assertThat(captured).allSatisfy(request -> {
			assertThat(request.authorization()).startsWith("AWS4-HMAC-SHA256");
			assertThat(request.sessionId()).isEqualTo("session-1");
		});
		assertThat(captured.get(1).body()).isEqualTo("{\"message\":\"\uC548\uB155\"}");
	}

	@Test
	void shouldSignDeleteWhenAStreamableHttpServerAssignsASession() throws Exception {
		List<CapturedRequest> captured = new CopyOnWriteArrayList<>();
		this.server = HttpServer.create(new InetSocketAddress(0), 0);
		this.server.createContext("/mcp", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			captured.add(new CapturedRequest(exchange.getRequestMethod(),
					exchange.getRequestHeaders().getFirst("Authorization"),
					exchange.getRequestHeaders().getFirst("Mcp-Session-Id"), body));
			if ("POST".equals(exchange.getRequestMethod()) && body.contains("\"initialize\"")) {
				Object requestId = new ObjectMapper().readValue(body, Map.class).get("id");
				String id = requestId instanceof String ? "\"" + requestId + "\"" : requestId.toString();
				byte[] response = ("""
						{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"local-session-test","version":"1.0.0"}}}
						""")
					.formatted(id)
					.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.getResponseHeaders().add("Mcp-Session-Id", "test-session");
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

		URI endpoint = URI.create("http://localhost:" + this.server.getAddress().getPort() + "/mcp");
		var signer = new AwsSigV4McpRequestCustomizer(() -> BASIC_CREDENTIALS, Region.AP_NORTHEAST_2,
				"bedrock-agentcore", endpoint);
		var transport = HttpClientStreamableHttpTransport.builder(endpoint.toString())
			.endpoint("/mcp")
			.asyncHttpRequestCustomizer(signer)
			.build();
		var client = McpClient.async(transport).build();

		client.initialize().block();
		client.closeGracefully().block();

		assertThat(captured).anySatisfy(request -> {
			assertThat(request.method()).isEqualTo("DELETE");
			assertThat(request.sessionId()).isEqualTo("test-session");
			assertThat(request.authorization()).startsWith("AWS4-HMAC-SHA256");
		});
	}

	@Test
	void shouldKeepDiagnosticStringFreeOfSecrets() {
		URI endpoint = URI.create("https://example.com/mcp");
		var customizer = new AwsSigV4McpRequestCustomizer(() -> BASIC_CREDENTIALS, Region.AP_NORTHEAST_2,
				"bedrock-agentcore", endpoint);
		assertThat(customizer.toString()).doesNotContain("AKIDEXAMPLE", "test-secret-key-value", endpoint.toString());
	}

	@Test
	void shouldLeaveAnUnboundEndpointUnsigned() {
		URI configuredEndpoint = URI.create("https://example.com/mcp");
		URI otherEndpoint = URI.create("https://other.example.com/mcp");
		var customizer = new AwsSigV4McpRequestCustomizer(() -> BASIC_CREDENTIALS, Region.AP_NORTHEAST_2,
				"bedrock-agentcore", configuredEndpoint);

		HttpRequest request = Mono.from(customizer.customize(requestBuilder("GET", otherEndpoint, null), "GET",
				otherEndpoint, null, McpTransportContext.EMPTY))
			.block()
			.build();

		assertThat(request.headers().firstValue("Authorization")).isEmpty();
	}

	private static HttpRequest sign(String method, URI uri, String body, AwsCredentialsProvider credentialsProvider) {
		return customize(requestBuilder(method, uri, body), method, uri, body, credentialsProvider);
	}

	private static HttpRequest customize(HttpRequest.Builder builder, String method, URI uri, String body,
			AwsCredentialsProvider credentialsProvider) {
		var customizer = new AwsSigV4McpRequestCustomizer(credentialsProvider, Region.AP_NORTHEAST_2,
				"bedrock-agentcore", uri);
		return Mono.from(customizer.customize(builder, method, uri, body, McpTransportContext.EMPTY)).block().build();
	}

	private static HttpRequest.Builder requestBuilder(String method, URI uri, String body) {
		HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
		return HttpRequest.newBuilder(uri).method(method, publisher);
	}

	private static String authorization(HttpRequest request) {
		return request.headers().firstValue("Authorization").orElseThrow();
	}

	private record CapturedRequest(String method, String authorization, String sessionId, String body) {
	}

}
