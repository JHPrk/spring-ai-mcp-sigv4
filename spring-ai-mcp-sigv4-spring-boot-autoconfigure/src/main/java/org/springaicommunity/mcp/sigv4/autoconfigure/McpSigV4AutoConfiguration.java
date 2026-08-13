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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.DelegatingMcpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.mcp.sigv4.AwsSigV4McpRequestCustomizer;
import org.springaicommunity.mcp.sigv4.RoutingAwsSigV4McpRequestCustomizer;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties.ConnectionParameters;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.ObjectProvider;
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
@AutoConfiguration
@ConditionalOnClass({ McpAsyncHttpClientRequestCustomizer.class, AwsV4HttpSigner.class,
		McpStreamableHttpClientProperties.class })
@ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({ McpAwsProperties.class, McpStreamableHttpClientProperties.class })
public class McpSigV4AutoConfiguration {

	/**
	 * Creates the default AWS credentials provider when the application has not supplied
	 * one. Spring manages and closes this provider with the application context.
	 * @return AWS SDK default credentials provider
	 */
	@Bean(destroyMethod = "close")
	@Conditional(OnAnyMcpAwsConnectionCondition.class)
	@ConditionalOnMissingBean(AwsCredentialsProvider.class)
	DefaultCredentialsProvider mcpSigV4AwsCredentialsProvider() {
		return DefaultCredentialsProvider.builder().build();
	}

	/**
	 * Creates the default AWS region provider chain when the application has not supplied
	 * one.
	 * @return AWS SDK default region provider chain
	 */
	@Bean
	@Conditional(OnAnyMcpAwsConnectionCondition.class)
	@ConditionalOnMissingBean(AwsRegionProvider.class)
	DefaultAwsRegionProviderChain mcpSigV4AwsRegionProvider() {
		return DefaultAwsRegionProviderChain.builder().build();
	}

	/**
	 * Creates a routing request customizer for the configured AWS-authenticated MCP
	 * connections.
	 * @param awsProperties AWS signing settings
	 * @param streamableProperties Spring AI Streamable HTTP connection settings
	 * @param credentialsProvider application-provided or auto-configured credentials
	 * provider
	 * @param regionProvider application-provided or auto-configured region provider
	 * @return request customizer that signs only configured endpoints
	 */
	@Bean
	@Conditional(OnAnyMcpAwsConnectionCondition.class)
	@ConditionalOnMissingBean(RoutingAwsSigV4McpRequestCustomizer.class)
	RoutingAwsSigV4McpRequestCustomizer awsSigV4McpRequestCustomizer(McpAwsProperties awsProperties,
			McpStreamableHttpClientProperties streamableProperties, AwsCredentialsProvider credentialsProvider,
			AwsRegionProvider regionProvider) {
		return createRoutingCustomizer(awsProperties, streamableProperties, credentialsProvider, regionProvider);
	}

	/**
	 * Installs the request customizer through the transport hook on Spring AI releases
	 * that do not natively collect request-customizer beans.
	 * @param awsProperties AWS signing settings
	 * @param requestCustomizer routing SigV4 request customizer
	 * @param syncRequestCustomizers application-provided synchronous request customizers
	 * @param asyncRequestCustomizers application-provided asynchronous request
	 * customizers
	 * @return compatibility transport customizer
	 */
	@Bean
	@Conditional({ OnAnyMcpAwsConnectionCondition.class, OnLegacyMcpHttpClientIntegrationCondition.class })
	@ConditionalOnMissingBean(LegacyMcpSigV4TransportCustomizer.class)
	McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> legacyMcpSigV4TransportCustomizer(
			McpAwsProperties awsProperties, RoutingAwsSigV4McpRequestCustomizer requestCustomizer,
			ObjectProvider<McpSyncHttpClientRequestCustomizer> syncRequestCustomizers,
			ObjectProvider<McpAsyncHttpClientRequestCustomizer> asyncRequestCustomizers) {
		McpAsyncHttpClientRequestCustomizer composed = composeRequestCustomizers(requestCustomizer,
				syncRequestCustomizers, asyncRequestCustomizers);
		return new LegacyMcpSigV4TransportCustomizer(awsProperties.getConnections().keySet(), composed);
	}

	private static McpAsyncHttpClientRequestCustomizer composeRequestCustomizers(
			RoutingAwsSigV4McpRequestCustomizer sigV4RequestCustomizer,
			ObjectProvider<McpSyncHttpClientRequestCustomizer> syncRequestCustomizers,
			ObjectProvider<McpAsyncHttpClientRequestCustomizer> asyncRequestCustomizers) {
		List<McpAsyncHttpClientRequestCustomizer> delegates = new ArrayList<>();
		syncRequestCustomizers.orderedStream()
			.map(McpAsyncHttpClientRequestCustomizer::fromSync)
			.forEach(delegates::add);
		asyncRequestCustomizers.orderedStream()
			.filter(customizer -> customizer != sigV4RequestCustomizer)
			.forEach(delegates::add);
		if (delegates.isEmpty()) {
			return sigV4RequestCustomizer;
		}
		delegates.add(sigV4RequestCustomizer);
		return new DelegatingMcpAsyncHttpClientRequestCustomizer(delegates);
	}

	static RoutingAwsSigV4McpRequestCustomizer createRoutingCustomizer(McpAwsProperties awsProperties,
			McpStreamableHttpClientProperties streamableProperties, AwsCredentialsProvider credentialsProvider,
			AwsRegionProvider regionProvider) {
		Map<URI, AwsSigV4McpRequestCustomizer> delegates = new LinkedHashMap<>();
		Map<URI, SigningScope> scopes = new LinkedHashMap<>();
		awsProperties.getConnections().forEach((name, aws) -> {
			Assert.hasText(name, "authorization.aws connection name must not be blank");
			Assert.notNull(aws, () -> "authorization.aws.connections.'" + name + "' must not be null");
			ConnectionParameters transport = streamableProperties.getConnections().get(name);
			Assert.state(transport != null,
					() -> "authorization.aws connection '" + name + "' has no matching streamable-http connection");
			URI endpoint = resolveEndpoint(name, transport);
			validateScheme(name, endpoint, aws.isAllowInsecureHttp());
			Region region = resolveRegion(aws.getRegion(), regionProvider);
			SigningScope scope = new SigningScope(region, aws.getServiceName());
			SigningScope existing = scopes.putIfAbsent(endpoint, scope);
			Assert.state(existing == null || existing.equals(scope),
					"MCP connections resolving to the same endpoint have conflicting AWS signing scopes");
			if (existing == null) {
				delegates.put(endpoint,
						new AwsSigV4McpRequestCustomizer(credentialsProvider, region, aws.getServiceName(), endpoint));
			}
		});
		return new RoutingAwsSigV4McpRequestCustomizer(delegates);
	}

	private static URI resolveEndpoint(String name, ConnectionParameters transport) {
		Assert.hasText(transport.url(), () -> "streamable-http connection '" + name + "' must configure a url");
		URI baseUri = URI.create(transport.url());
		String endpoint = StringUtils.hasText(transport.endpoint()) ? transport.endpoint() : "/mcp";
		return baseUri.resolve(endpoint).normalize();
	}

	private static void validateScheme(String name, URI endpoint, boolean allowInsecureHttp) {
		Assert.state(endpoint.isAbsolute(), () -> "streamable-http connection '" + name + "' url must be absolute");
		String scheme = endpoint.getScheme();
		Assert.state("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme),
				() -> "streamable-http connection '" + name + "' must use HTTP or HTTPS");
		Assert.state(!"http".equalsIgnoreCase(scheme) || allowInsecureHttp,
				() -> "refusing to send SigV4 credentials over insecure HTTP for connection '" + name
						+ "'; set allow-insecure-http=true only for trusted local tests");
	}

	private static Region resolveRegion(@Nullable String configuredRegion, AwsRegionProvider regionProvider) {
		return StringUtils.hasText(configuredRegion) ? Region.of(configuredRegion) : regionProvider.getRegion();
	}

	private record SigningScope(Region region, String serviceName) {
	}

}
