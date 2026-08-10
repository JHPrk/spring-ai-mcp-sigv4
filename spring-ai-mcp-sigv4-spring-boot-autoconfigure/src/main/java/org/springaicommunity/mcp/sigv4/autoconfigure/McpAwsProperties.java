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

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Configuration properties for AWS SigV4 authentication of Spring AI MCP clients.
 *
 * <p>
 * Each key in {@link #getConnections() connections} must match a key under
 * {@code spring.ai.mcp.client.streamable-http.connections}. Only matching connections are
 * signed.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = McpAwsProperties.CONFIG_PREFIX)
public class McpAwsProperties {

	/** Configuration prefix for MCP AWS authentication. */
	public static final String CONFIG_PREFIX = "spring.ai.mcp.client.authorization.aws";

	/** Default SigV4 service name for Amazon Bedrock AgentCore Gateway. */
	public static final String DEFAULT_SERVICE_NAME = "bedrock-agentcore";

	private final Map<String, Connection> connections = new HashMap<>();

	/**
	 * Returns AWS authentication settings keyed by MCP connection name.
	 * @return mutable connection settings map used by configuration binding
	 */
	public Map<String, Connection> getConnections() {
		return this.connections;
	}

	/**
	 * AWS SigV4 options for one named MCP connection.
	 *
	 * @since 0.1.0
	 */
	public static class Connection {

		private String serviceName = DEFAULT_SERVICE_NAME;

		private @Nullable String region;

		private boolean allowInsecureHttp;

		/**
		 * Returns the AWS SigV4 service signing name.
		 * @return configured name or {@value McpAwsProperties#DEFAULT_SERVICE_NAME}
		 */
		public String getServiceName() {
			return StringUtils.hasText(this.serviceName) ? this.serviceName : DEFAULT_SERVICE_NAME;
		}

		/**
		 * Sets the AWS SigV4 service signing name.
		 * @param serviceName service signing name
		 */
		public void setServiceName(String serviceName) {
			this.serviceName = serviceName;
		}

		/**
		 * Returns the signing region, or {@code null} to use the AWS region provider
		 * chain.
		 * @return configured AWS region
		 */
		public @Nullable String getRegion() {
			return StringUtils.hasText(this.region) ? this.region : null;
		}

		/**
		 * Sets the signing region, or {@code null} to use the AWS region provider chain.
		 * @param region AWS region identifier
		 */
		public void setRegion(@Nullable String region) {
			this.region = region;
		}

		/**
		 * Returns whether signing over clear-text HTTP is explicitly allowed.
		 * @return {@code true} only when insecure HTTP was explicitly enabled
		 */
		public boolean isAllowInsecureHttp() {
			return this.allowInsecureHttp;
		}

		/**
		 * Sets whether clear-text HTTP is permitted for trusted local tests.
		 * @param allowInsecureHttp whether clear-text HTTP is permitted
		 */
		public void setAllowInsecureHttp(boolean allowInsecureHttp) {
			this.allowInsecureHttp = allowInsecureHttp;
		}

	}

}
