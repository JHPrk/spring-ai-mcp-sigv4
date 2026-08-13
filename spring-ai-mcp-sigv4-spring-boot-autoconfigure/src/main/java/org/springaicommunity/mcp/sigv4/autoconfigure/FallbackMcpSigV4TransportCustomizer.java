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
 * Fallback bridge that installs the composed MCP request customizers through Spring AI's
 * named transport hook.
 *
 * <p>
 * This is not a Spring AI 1.x compatibility layer. It is used only within the supported
 * Spring AI 2.0.x line when the detected MCP HTTP integration shape does not natively
 * collect and compose request-customizer beans. The auto-configuration composes
 * application-provided synchronous and asynchronous request customizers, appends the
 * routing SigV4 customizer, and supplies that composition to this bridge.
 * </p>
 *
 * <p>
 * When native request-customizer composition is available, this bridge is not registered.
 * Its presence therefore expresses a missing native capability, not support for Spring AI
 * 1.x.
 * </p>
 */
final class FallbackMcpSigV4TransportCustomizer
		implements McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> {

	private final Set<String> connectionNames;

	private final McpAsyncHttpClientRequestCustomizer requestCustomizer;

	FallbackMcpSigV4TransportCustomizer(Set<String> connectionNames,
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
