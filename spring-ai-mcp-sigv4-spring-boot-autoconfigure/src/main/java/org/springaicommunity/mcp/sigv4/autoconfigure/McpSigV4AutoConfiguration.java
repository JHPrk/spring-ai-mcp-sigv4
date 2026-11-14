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

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.mcp.sigv4.AwsSigV4HeaderSigningPolicies;
import org.springaicommunity.mcp.sigv4.AwsSigV4HeaderSigningPolicy;
import org.springaicommunity.mcp.sigv4.AwsSigV4McpRequestCustomizer;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties.ConnectionParameters;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Auto-configuration for connection-scoped AWS SigV4 authentication of Spring AI MCP
 * Streamable HTTP clients.
 *
 * @since 0.1.0
 */
@AutoConfiguration(afterName = {
		"org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration",
		"io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration",
		"io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration" })
@ConditionalOnClass({ McpAsyncHttpClientRequestCustomizer.class, AwsV4HttpSigner.class,
		McpStreamableHttpClientProperties.class })
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional(OnAnyMcpAwsConnectionCondition.class)
@EnableConfigurationProperties({ McpAwsProperties.class, McpStreamableHttpClientProperties.class })
public class McpSigV4AutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(AwsSigV4HeaderSigningPolicy.class)
	AwsSigV4HeaderSigningPolicy mcpSigV4HeaderSigningPolicy() {
		return AwsSigV4HeaderSigningPolicies.defaultPolicy();
	}

	/**
	 * Create the default AWS credentials provider when the application has not supplied
	 * one. Spring manages and closes this provider with the application context.
	 * @return AWS SDK default credentials provider
	 */
	@Bean(destroyMethod = "close")
	@ConditionalOnMissingBean(AwsCredentialsProvider.class)
	DefaultCredentialsProvider mcpSigV4AwsCredentialsProvider() {
		return DefaultCredentialsProvider.builder().build();
	}

	/**
	 * Create the default AWS region provider chain when the application has not supplied
	 * one.
	 * @return AWS SDK default region provider chain
	 */
	@Bean
	@ConditionalOnMissingBean(AwsRegionProvider.class)
	DefaultAwsRegionProviderChain mcpSigV4AwsRegionProvider() {
		return DefaultAwsRegionProviderChain.builder().build();
	}

	/**
	 * Appends signing only to the configured named transports. Signers are deliberately
	 * not request-customizer beans: Spring AI would install such beans on every
	 * connection, including unsigned aliases of the same endpoint.
	 * @param awsProperties AWS signing settings
	 * @param streamableProperties Spring AI Streamable HTTP connection settings
	 * @param credentialsProvider application-provided or auto-configured credentials
	 * provider
	 * @param regionProvider application-provided or auto-configured region provider
	 * @param headerSigningPolicy application-provided or default base signing policy
	 * @return named transport customizer preserving previously installed request
	 * customizers
	 */
	@Bean
	@ConditionalOnMissingBean(McpSigV4TransportCustomizer.class)
	McpSigV4TransportCustomizer awsSigV4McpTransportCustomizer(McpAwsProperties awsProperties,
			McpStreamableHttpClientProperties streamableProperties, AwsCredentialsProvider credentialsProvider,
			AwsRegionProvider regionProvider, AwsSigV4HeaderSigningPolicy headerSigningPolicy) {
		return new McpSigV4TransportCustomizer(createCustomizers(awsProperties, streamableProperties,
				credentialsProvider, regionProvider, headerSigningPolicy));
	}

	private static Map<String, AwsSigV4McpRequestCustomizer> createCustomizers(McpAwsProperties awsProperties,
			McpStreamableHttpClientProperties streamableProperties, AwsCredentialsProvider credentialsProvider,
			AwsRegionProvider regionProvider, AwsSigV4HeaderSigningPolicy headerSigningPolicy) {
		Map<String, AwsSigV4McpRequestCustomizer> customizers = new LinkedHashMap<>();
		awsProperties.getConnections().forEach((name, aws) -> {
			Assert.hasText(name, "mcp.sigv4 connection name must not be blank");
			Assert.notNull(aws, () -> "mcp.sigv4.connections.'" + name + "' must not be null");
			ConnectionParameters transport = streamableProperties.getConnections().get(name);
			Assert.state(transport != null,
					() -> "mcp.sigv4 connection '" + name + "' has no matching streamable-http connection");
			URI endpoint = resolveEndpoint(name, transport);
			validateScheme(name, endpoint, aws.isAllowInsecureHttp());
			Region region = resolveRegion(aws.getRegion(), regionProvider);
			Set<String> additionalUnsignedHeaders = aws.getSigning().getAdditionalUnsignedHeaders();
			customizers.put(name, new AwsSigV4McpRequestCustomizer(credentialsProvider, region, aws.getServiceName(),
					endpoint, composeHeaderSigningPolicy(headerSigningPolicy, additionalUnsignedHeaders)));
		});
		return customizers;
	}

	private static AwsSigV4HeaderSigningPolicy composeHeaderSigningPolicy(AwsSigV4HeaderSigningPolicy base,
			Set<String> additionalUnsignedHeaders) {
		AwsSigV4HeaderSigningPolicy additional = AwsSigV4HeaderSigningPolicies.excluding(additionalUnsignedHeaders);
		return name -> base.shouldSign(name) && additional.shouldSign(name);
	}

	private static URI resolveEndpoint(String name, ConnectionParameters transport) {
		Assert.hasText(transport.url(), () -> "streamable-http connection '" + name + "' must configure a url");
		try {
			URI baseUri = URI.create(transport.url());
			Assert.state(baseUri.getRawUserInfo() == null && baseUri.getRawFragment() == null,
					() -> "streamable-http connection '" + name + "' must not contain user info or a fragment");
			String endpoint = StringUtils.hasText(transport.endpoint()) ? transport.endpoint() : "/mcp";
			return baseUri.resolve(endpoint).normalize();
		}
		catch (IllegalArgumentException ex) {
			// URI parsing exceptions include their input, which may be a private
			// endpoint.
			throw new IllegalArgumentException("invalid MCP endpoint configuration for connection '" + name + "'");
		}
	}

	private static void validateScheme(String name, URI endpoint, boolean allowInsecureHttp) {
		Assert.state(endpoint.isAbsolute(), () -> "streamable-http connection '" + name + "' url must be absolute");
		String scheme = endpoint.getScheme();
		Assert.state("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme),
				() -> "streamable-http connection '" + name + "' must use HTTP or HTTPS");
		Assert.state(endpoint.getHost() != null,
				() -> "streamable-http connection '" + name + "' must configure a valid host");
		Assert.state(endpoint.getRawUserInfo() == null && endpoint.getRawFragment() == null,
				() -> "streamable-http connection '" + name + "' must not contain user info or a fragment");
		Assert.state(!"http".equalsIgnoreCase(scheme) || allowInsecureHttp,
				() -> "refusing to send SigV4 credentials over insecure HTTP for connection '" + name
						+ "'; set allow-insecure-http=true only for trusted local tests");
	}

	private static Region resolveRegion(@Nullable String configuredRegion, AwsRegionProvider regionProvider) {
		return StringUtils.hasText(configuredRegion) ? Region.of(configuredRegion) : regionProvider.getRegion();
	}

}
