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
import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * Routes requests to an AWS SigV4 customizer by their exact MCP endpoint URI.
 *
 * <p>
 * Requests for endpoints that are not configured for AWS authentication are returned
 * unchanged. The customizer runs with the lowest precedence so headers contributed by
 * other request customizers are included in the signature.
 * </p>
 *
 * @since 0.1.0
 */
public final class RoutingAwsSigV4McpRequestCustomizer implements McpAsyncHttpClientRequestCustomizer, Ordered {

	private final Map<URI, AwsSigV4McpRequestCustomizer> delegates;

	/**
	 * Creates a routing customizer.
	 * @param delegates exact normalized endpoint URI to SigV4 customizer mappings
	 * @since 0.1.0
	 */
	public RoutingAwsSigV4McpRequestCustomizer(Map<URI, AwsSigV4McpRequestCustomizer> delegates) {
		Assert.notEmpty(delegates, "delegates must not be empty");
		Map<URI, AwsSigV4McpRequestCustomizer> normalized = new LinkedHashMap<>();
		delegates.forEach((endpoint, customizer) -> {
			Assert.notNull(endpoint, "endpoint must not be null");
			Assert.notNull(customizer, "customizer must not be null");
			Assert.isTrue(customizer.supports(endpoint), "customizer endpoint must match its routing endpoint");
			AwsSigV4McpRequestCustomizer existing = normalized.putIfAbsent(endpoint.normalize(), customizer);
			Assert.state(existing == null, () -> "duplicate MCP endpoint: " + endpoint);
		});
		this.delegates = Map.copyOf(normalized);
	}

	@Override
	public Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method, URI endpoint,
			@Nullable String body, McpTransportContext context) {
		AwsSigV4McpRequestCustomizer delegate = this.delegates.get(endpoint.normalize());
		return delegate != null ? delegate.customize(builder, method, endpoint, body, context) : Mono.just(builder);
	}

	/**
	 * Returns whether this customizer signs the given exact endpoint.
	 * @param endpoint MCP request endpoint
	 * @return {@code true} when the endpoint has an AWS signing configuration
	 * @since 0.1.0
	 */
	public boolean supports(URI endpoint) {
		return this.delegates.containsKey(endpoint.normalize());
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public String toString() {
		return "RoutingAwsSigV4McpRequestCustomizer[endpointCount=" + this.delegates.size() + "]";
	}

}
