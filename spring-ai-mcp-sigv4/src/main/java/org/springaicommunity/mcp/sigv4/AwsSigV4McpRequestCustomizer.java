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
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.regions.Region;

import org.springframework.util.Assert;

/**
 * Applies AWS Signature Version 4 immediately before the MCP JDK HTTP transport sends a
 * request.
 *
 * <p>
 * Credentials are resolved for each request on Reactor's bounded-elastic scheduler. The
 * customizer does not retain credential values or signed authorization headers.
 * </p>
 *
 * <p>
 * Requests that already contain a SigV4-owned header are rejected before credentials are
 * resolved. Other application and MCP headers are preserved on the wire; eligible headers
 * are included in the signature according to the header signing policy.
 * </p>
 *
 * @since 0.1.0
 */
public final class AwsSigV4McpRequestCustomizer implements McpAsyncHttpClientRequestCustomizer {

	static final Set<String> SIGV4_OWNED_HEADERS = Set.of("authorization", "x-amz-date", "x-amz-security-token",
			"x-amz-content-sha256");

	private final AwsCredentialsProvider credentialsProvider;

	private final Region region;

	private final String serviceName;

	private final URI endpoint;

	private final AwsSigV4HeaderSigningPolicy headerSigningPolicy;

	private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();

	private final AwsV4HttpRequestAdapter requestAdapter = new AwsV4HttpRequestAdapter();

	/**
	 * Creates an MCP SigV4 request customizer.
	 * @param credentialsProvider provider resolved for every HTTP request
	 * @param region signing region used in the credential scope
	 * @param serviceName signing service name, typically {@code bedrock-agentcore}
	 * @param endpoint exact normalized MCP endpoint this customizer may sign
	 * @since 0.1.0
	 */
	public AwsSigV4McpRequestCustomizer(AwsCredentialsProvider credentialsProvider, Region region, String serviceName,
			URI endpoint) {
		this(credentialsProvider, region, serviceName, endpoint, AwsSigV4HeaderSigningPolicies.defaultPolicy());
	}

	/**
	 * Creates a customizer with an explicit header signing policy.
	 * @param credentialsProvider provider resolved for every HTTP request
	 * @param region signing region used in the credential scope
	 * @param serviceName signing service name
	 * @param endpoint exact normalized MCP endpoint this customizer may sign
	 * @param headerSigningPolicy thread-safe policy selecting existing headers for
	 * signing; excluded headers remain on the original request
	 * @since 0.1.0
	 */
	public AwsSigV4McpRequestCustomizer(AwsCredentialsProvider credentialsProvider, Region region, String serviceName,
			URI endpoint, AwsSigV4HeaderSigningPolicy headerSigningPolicy) {
		Assert.notNull(credentialsProvider, "credentialsProvider must not be null");
		Assert.notNull(region, "region must not be null");
		Assert.hasText(serviceName, "serviceName must not be blank");
		Assert.notNull(endpoint, "endpoint must not be null");
		Assert.isTrue(endpoint.isAbsolute(), "endpoint must be absolute");
		this.credentialsProvider = credentialsProvider;
		this.region = region;
		this.serviceName = serviceName;
		this.endpoint = endpoint.normalize();
		Assert.notNull(headerSigningPolicy, "headerSigningPolicy must not be null");
		this.headerSigningPolicy = headerSigningPolicy;
	}

	@Override
	public Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method, URI endpoint,
			@Nullable String body, McpTransportContext context) {
		if (!supports(endpoint)) {
			return Mono.just(builder);
		}
		HttpRequest snapshot = builder.build();
		Map<String, List<String>> wireHeaders = snapshot.headers().map();
		boolean hasSigV4OwnedHeader = wireHeaders.keySet()
			.stream()
			.map(name -> name.toLowerCase(Locale.ROOT))
			.anyMatch(SIGV4_OWNED_HEADERS::contains);
		Assert.state(!hasSigV4OwnedHeader, "AWS SigV4-owned headers must not be set before signing");
		Map<String, List<String>> signingHeaders = new LinkedHashMap<>();
		wireHeaders.forEach((name, values) -> {
			if (this.headerSigningPolicy.shouldSign(name.toLowerCase(Locale.ROOT))) {
				signingHeaders.put(name, values);
			}
		});
		var sdkRequest = this.requestAdapter.adapt(method, endpoint, signingHeaders);
		return Mono.fromCallable(this.credentialsProvider::resolveCredentials)
			.subscribeOn(Schedulers.boundedElastic())
			.map(credentials -> sign(builder, sdkRequest, body, credentials));
	}

	private HttpRequest.Builder sign(HttpRequest.Builder builder, SdkHttpRequest sdkRequest, @Nullable String body,
			AwsCredentials credentials) {
		SignedRequest signedRequest = this.signer.sign(signRequest -> {
			signRequest.identity(credentials)
				.request(sdkRequest)
				.putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, this.serviceName)
				.putProperty(AwsV4HttpSigner.REGION_NAME, this.region.id())
				.putProperty(HttpSigner.SIGNING_CLOCK, Clock.systemUTC());
			if (body != null) {
				signRequest.payload(ContentStreamProvider.fromUtf8String(body));
			}
		});
		return this.requestAdapter.applyRequiredHeaders(builder, signedRequest.request().headers());
	}

	/**
	 * Returns whether this customizer signs the given exact endpoint.
	 * @param endpoint MCP request endpoint
	 * @return {@code true} when the endpoint matches this customizer's signing scope
	 * @since 0.1.0
	 */
	public boolean supports(URI endpoint) {
		return this.endpoint.equals(endpoint.normalize());
	}

	@Override
	public String toString() {
		return "AwsSigV4McpRequestCustomizer[region=" + this.region.id() + ", serviceName=" + this.serviceName + "]";
	}

}
