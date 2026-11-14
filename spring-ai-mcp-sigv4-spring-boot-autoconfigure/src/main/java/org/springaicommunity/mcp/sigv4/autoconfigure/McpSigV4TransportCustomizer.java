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

import java.util.Map;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springaicommunity.mcp.sigv4.AwsSigV4McpRequestCustomizer;

import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.util.Assert;

/**
 * Appends SigV4 to the SDK's existing request pipeline only for named AWS connections.
 * Application request customizers installed by Spring AI run before signing. Applications
 * must register any additional transport customizers before this auto-configured signer;
 * changing the request after signing can invalidate it.
 *
 * @since 0.1.0
 */
final class McpSigV4TransportCustomizer implements McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> {

	private final Map<String, AwsSigV4McpRequestCustomizer> customizers;

	McpSigV4TransportCustomizer(Map<String, AwsSigV4McpRequestCustomizer> customizers) {
		Assert.notEmpty(customizers, "customizers must not be empty");
		this.customizers = Map.copyOf(customizers);
	}

	@Override
	public void customize(String name, HttpClientStreamableHttpTransport.Builder builder) {
		AwsSigV4McpRequestCustomizer customizer = this.customizers.get(name);
		if (customizer != null) {
			builder.addAsyncHttpRequestCustomizer(customizer);
		}
	}

}
