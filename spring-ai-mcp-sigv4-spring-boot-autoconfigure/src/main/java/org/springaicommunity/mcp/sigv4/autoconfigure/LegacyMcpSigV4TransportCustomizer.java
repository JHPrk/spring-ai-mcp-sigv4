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

import java.util.Set;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;

import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.util.Assert;

/**
 * Installs SigV4 through the named transport hook used by Spring AI 2.0.0.
 *
 * <p>
 * This bridge is not registered when Spring AI natively collects HTTP request-customizer
 * beans.
 * </p>
 */
final class LegacyMcpSigV4TransportCustomizer
		implements McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> {

	private final Set<String> connectionNames;

	private final McpAsyncHttpClientRequestCustomizer requestCustomizer;

	LegacyMcpSigV4TransportCustomizer(Set<String> connectionNames,
			McpAsyncHttpClientRequestCustomizer requestCustomizer) {
		Assert.notEmpty(connectionNames, "connectionNames must not be empty");
		this.connectionNames = Set.copyOf(connectionNames);
		this.requestCustomizer = requestCustomizer;
	}

	@Override
	public void customize(String name, HttpClientStreamableHttpTransport.Builder builder) {
		if (this.connectionNames.contains(name)) {
			builder.asyncHttpRequestCustomizer(this.requestCustomizer);
		}
	}

}
