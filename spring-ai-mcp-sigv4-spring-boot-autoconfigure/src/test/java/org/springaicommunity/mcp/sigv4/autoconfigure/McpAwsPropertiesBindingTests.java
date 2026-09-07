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

import org.junit.jupiter.api.Test;

import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class McpAwsPropertiesBindingTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(BindingConfiguration.class)
		.withPropertyValues(
				"spring.ai.mcp.client.streamable-http.connections.agentcore.url=https://gw.example.com/base/",
				"spring.ai.mcp.client.streamable-http.connections.agentcore.endpoint=mcp",
				"spring.ai.mcp.client.streamable-http.connections.public.url=https://public.example.com",
				"spring.ai.mcp.client.authorization.aws.connections.agentcore.region=ap-northeast-2");

	@Test
	void bindsTransportAndAuthenticationPropertiesSeparately() {
		this.contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			McpStreamableHttpClientProperties transport = context.getBean(McpStreamableHttpClientProperties.class);
			assertThat(transport.getConnections()).containsOnlyKeys("agentcore", "public");
			assertThat(transport.getConnections().get("agentcore").url()).isEqualTo("https://gw.example.com/base/");
			assertThat(transport.getConnections().get("agentcore").endpoint()).isEqualTo("mcp");

			McpAwsProperties aws = context.getBean(McpAwsProperties.class);
			assertThat(aws.getConnections()).containsOnlyKeys("agentcore");
			assertThat(aws.getConnections().get("agentcore").getServiceName())
				.isEqualTo(McpAwsProperties.DEFAULT_SERVICE_NAME);
			assertThat(aws.getConnections().get("agentcore").getRegion()).isEqualTo("ap-northeast-2");
			assertThat(aws.getConnections().get("agentcore").isAllowInsecureHttp()).isFalse();
		});
	}

	@Test
	void additionalUnsignedHeadersDefaultToEmpty() {
		this.contextRunner.run(context -> assertThat(context.getBean(McpAwsProperties.class)
			.getConnections()
			.get("agentcore")
			.getSigning()
			.getAdditionalUnsignedHeaders()).isEmpty());
	}

	@Test
	void bindsNormalizesAndDeduplicatesUnsignedHeaders() {
		String prefix = McpAwsProperties.CONFIG_PREFIX + ".connections.agentcore.signing.additional-unsigned-headers";
		this.contextRunner
			.withPropertyValues(prefix + "[0]=X-Company-Trace", prefix + "[1]=x-company-trace",
					prefix + "[2]= X-Another ")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(McpAwsProperties.class)
					.getConnections()
					.get("agentcore")
					.getSigning()
					.getAdditionalUnsignedHeaders()).containsExactlyInAnyOrder("x-company-trace", "x-another");
			});
	}

	@Test
	void rejectsBlankUnsignedHeader() {
		this.contextRunner
			.withPropertyValues(
					McpAwsProperties.CONFIG_PREFIX + ".connections.agentcore.signing.additional-unsigned-headers[0]= ")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.hasRootCauseMessage("additional unsigned header names must not be blank");
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties({ McpStreamableHttpClientProperties.class, McpAwsProperties.class })
	static class BindingConfiguration {

	}

}
