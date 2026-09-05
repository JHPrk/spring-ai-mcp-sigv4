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
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.regions.Region;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local wire regression; the dedicated Gradle task forks this JVM with the real agent.
 */
@Tag("otel-agent")
@Timeout(20)
class OtelAgentSigningTests {

	private static final AwsSessionCredentials CREDENTIALS = AwsSessionCredentials.create("test-key", "test-secret",
			"test-session-token");

	private static final SpanContext PARENT = SpanContext.create("11111111111111111111111111111111", "2222222222222222",
			TraceFlags.getSampled(), TraceState.builder().put("test", "parent").build());

	@ParameterizedTest
	@CsvSource({ "GET,false", "POST,false", "DELETE,false", "GET,true", "POST,true", "DELETE,true" })
	void defaultPolicySurvivesRealSendTimePropagation(String method, boolean async) throws Exception {
		Exchange exchange = exchange(method, async, AwsSigV4HeaderSigningPolicies.defaultPolicy());
		assertPropagationChanged(exchange);
		List<String> signed = signedHeaders(exchange.wireHeaders());
		assertThat(signed).doesNotContain("traceparent", "tracestate", "baggage");
		assertThat(signed).contains("content-type", "mcp-protocol-version", "mcp-session-id", "last-event-id",
				"x-tenant", "host", "x-amz-date", "x-amz-security-token");
		assertThat(exchange.wireBody()).isEqualTo(exchange.bodyAtSigning());
		assertThat(validSignature(exchange, exchange.wireHeaders(), exchange.wireBody())).isTrue();
		Map<String, List<String>> changedStableHeader = new LinkedHashMap<>(exchange.wireHeaders());
		changedStableHeader.put("x-tenant", List.of("changed"));
		assertThat(validSignature(exchange, changedStableHeader, exchange.wireBody())).isFalse();
		assertThat(validSignature(exchange, exchange.wireHeaders(), "changed-body")).isFalse();
		if ("POST".equals(method)) {
			assertThat(signed).contains("x-amz-content-sha256");
			String payloadHash = HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256")
					.digest(exchange.wireBody().getBytes(StandardCharsets.UTF_8)));
			assertThat(header(exchange.wireHeaders(), "x-amz-content-sha256").equals(payloadHash)).isTrue();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void allPolicyReproducesSignatureMismatchWithSameInstrumentation(boolean async) throws Exception {
		Exchange exchange = exchange("POST", async, AwsSigV4HeaderSigningPolicies.all());
		assertPropagationChanged(exchange);
		assertThat(signedHeaders(exchange.wireHeaders())).contains("traceparent", "tracestate", "baggage", "x-tenant");
		assertThat(validSignature(exchange, exchange.wireHeaders(), exchange.wireBody())).isFalse();
		Map<String, List<String>> signingView = new LinkedHashMap<>(exchange.wireHeaders());
		signingView.put("traceparent", List.of(exchange.traceparentAtSigning()));
		assertThat(validSignature(exchange, signingView, exchange.wireBody())).isTrue();
	}

	private static void assertPropagationChanged(Exchange exchange) {
		String wireParent = header(exchange.wireHeaders(), "traceparent");
		assertThat(wireParent).isNotEqualTo(exchange.traceparentAtSigning());
		assertThat(wireParent.split("-")[1]).isEqualTo(PARENT.getTraceId());
		assertThat(wireParent.split("-")[2]).isNotEqualTo(PARENT.getSpanId());
		assertThat(header(exchange.wireHeaders(), "tracestate")).isEqualTo("test=parent");
		assertThat(header(exchange.wireHeaders(), "baggage")).isEqualTo("test-baggage=value");
	}

	@SuppressWarnings("try")
	private static Exchange exchange(String method, boolean async, AwsSigV4HeaderSigningPolicy policy)
			throws Exception {
		CompletableFuture<Capture> captured = new CompletableFuture<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", request -> {
			try {
				Map<String, List<String>> headers = new LinkedHashMap<>();
				request.getRequestHeaders()
					.forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT),
							List.copyOf(values)));
				captured.complete(new Capture(Map.copyOf(headers),
						new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
				request.sendResponseHeaders(204, -1);
			}
			catch (Exception ex) {
				captured.completeExceptionally(ex);
			}
			finally {
				request.close();
			}
		});
		server.start();
		try {
			URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
			String body = "POST".equals(method) ? "{\"message\":\"\uC548\uB155\"}" : "";
			Context parent = Baggage.builder()
				.put("test-baggage", "value")
				.build()
				.storeInContext(Span.wrap(PARENT).storeInContext(Context.root()));
			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.version(HttpClient.Version.HTTP_1_1)
				.build();
			try (Scope scope = parent.makeCurrent()) {
				var builder = HttpRequest.newBuilder(endpoint)
					.timeout(Duration.ofSeconds(5))
					.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
					.header("traceparent", "00-" + PARENT.getTraceId() + "-" + PARENT.getSpanId() + "-01")
					.header("tracestate", "test=parent")
					.header("baggage", "test-baggage=value")
					.header("Content-Type", "application/json")
					.header("Mcp-Protocol-Version", "2025-06-18")
					.header("Mcp-Session-Id", "local-session")
					.header("Last-Event-Id", "local-event")
					.header("X-Tenant", "stable");
				String atSigning = builder.build().headers().firstValue("traceparent").orElseThrow();
				var signer = new AwsSigV4McpRequestCustomizer(() -> CREDENTIALS, Region.US_EAST_1, "bedrock-agentcore",
						endpoint, policy);
				HttpRequest request = Mono.from(signer.customize(builder, method, endpoint,
						"POST".equals(method) ? body : null, McpTransportContext.EMPTY))
					.block()
					.build();
				HttpResponse<Void> response = async
						? client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).get(5, TimeUnit.SECONDS)
						: client.send(request, HttpResponse.BodyHandlers.discarding());
				assertThat(response.statusCode()).isEqualTo(204);
				Capture wire = captured.get(5, TimeUnit.SECONDS);
				return new Exchange(endpoint, method, body, atSigning, wire.headers(), wire.body());
			}
		}
		finally {
			server.stop(0);
		}
	}

	// Recompute with the public AWS signer from captured wire values and original signing
	// time. This checks the contract without duplicating canonicalization or HMAC
	// internals.
	private static boolean validSignature(Exchange exchange, Map<String, List<String>> wireHeaders, String body) {
		Map<String, List<String>> signingHeaders = new LinkedHashMap<>();
		for (String name : signedHeaders(wireHeaders)) {
			if (!AwsSigV4McpRequestCustomizer.SIGV4_OWNED_HEADERS.contains(name)) {
				signingHeaders.put(name, wireHeaders.get(name));
			}
		}
		Clock signingClock = Clock.fixed(LocalDateTime
			.parse(header(wireHeaders, "x-amz-date"),
					DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.ROOT))
			.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		var sdkRequest = SdkHttpRequest.builder()
			.uri(exchange.endpoint())
			.method(SdkHttpMethod.fromValue(exchange.method()))
			.headers(signingHeaders)
			.build();
		var signed = AwsV4HttpSigner.create().sign(request -> {
			request.identity(CREDENTIALS)
				.request(sdkRequest)
				.putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "bedrock-agentcore")
				.putProperty(AwsV4HttpSigner.REGION_NAME, "us-east-1")
				.putProperty(HttpSigner.SIGNING_CLOCK, signingClock);
			if ("POST".equals(exchange.method()) || !body.isEmpty()) {
				request.payload(ContentStreamProvider.fromUtf8String(body));
			}
		});
		// Assert only a boolean so even a failing test cannot print signing material.
		return signed.request()
			.firstMatchingHeader("Authorization")
			.orElseThrow()
			.equals(header(wireHeaders, "authorization"));
	}

	private static List<String> signedHeaders(Map<String, List<String>> headers) {
		return List.of(header(headers, "authorization").split("SignedHeaders=", 2)[1].split(",", 2)[0].split(";"));
	}

	private static String header(Map<String, List<String>> headers, String name) {
		return headers.get(name).get(0);
	}

	private record Capture(Map<String, List<String>> headers, String body) {
	}

	private record Exchange(URI endpoint, String method, String bodyAtSigning, String traceparentAtSigning,
			Map<String, List<String>> wireHeaders, String wireBody) {
	}

}
