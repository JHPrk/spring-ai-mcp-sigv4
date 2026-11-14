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

import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration;
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;

import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

class CloudAwsCompatibilityTests {

	@Test
	void usesCloudAwsCredentialsAndRegionWithoutDefaultDuplicates() {
		contextRunner().run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(AwsCredentialsProvider.class);
			assertThat(context).hasSingleBean(AwsRegionProvider.class);
			assertThat(context).hasSingleBean(McpClientCustomizer.class);
			assertThat(context.getBean(AwsCredentialsProvider.class)).isInstanceOf(StaticCredentialsProvider.class);
			assertThat(context.getBean(AwsCredentialsProvider.class).resolveCredentials().accessKeyId())
				.isEqualTo("cloud-test-key");
			assertThat(context.getBean(AwsRegionProvider.class).getRegion()).isEqualTo(Region.AP_NORTHEAST_2);
			assertThat(context.containsBean("mcpSigV4AwsCredentialsProvider")).isFalse();
			assertThat(context.containsBean("mcpSigV4AwsRegionProvider")).isFalse();
		});
	}

	@Test
	void primaryApplicationProviderWinsWhenSeveralProvidersExist() {
		contextRunner().withUserConfiguration(UserProviders.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(AwsCredentialsProvider.class)).hasSize(2);
			assertThat(context.getBean(AwsCredentialsProvider.class)).isSameAs(context.getBean("primaryCredentials"));
			assertThat(context.getBean(AwsRegionProvider.class).getRegion()).isEqualTo(Region.US_WEST_2);
			assertThat(context).hasSingleBean(McpClientCustomizer.class);
		});
	}

	@Test
	void missingSignerBacksOff() {
		contextRunner().withClassLoader(new FilteredClassLoader(AwsV4HttpSigner.class)).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(McpClientCustomizer.class);
			assertThat(context.containsBean("mcpSigV4AwsCredentialsProvider")).isFalse();
		});
	}

	@Test
	void missingSpringAiTransportPropertiesBacksOff() {
		contextRunner().withClassLoader(new FilteredClassLoader(McpStreamableHttpClientProperties.class))
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(McpClientCustomizer.class);
			});
	}

	@Test
	void disablingMcpLeavesCloudAwsProvidersAlone() {
		contextRunner().withPropertyValues("spring.ai.mcp.client.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(McpClientCustomizer.class);
			assertThat(context).hasSingleBean(AwsCredentialsProvider.class);
			assertThat(context).hasSingleBean(AwsRegionProvider.class);
		});
	}

	private ApplicationContextRunner contextRunner() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(McpSigV4AutoConfiguration.class,
					CredentialsProviderAutoConfiguration.class, RegionProviderAutoConfiguration.class))
			.withPropertyValues("spring.cloud.aws.region.static=ap-northeast-2",
					"spring.cloud.aws.credentials.access-key=cloud-test-key",
					"spring.cloud.aws.credentials.secret-key=cloud-test-secret",
					"spring.ai.mcp.client.streamable-http.connections.gateway.url=https://gateway.example",
					"mcp.sigv4.connections.gateway.service-name=bedrock-agentcore");
	}

	@Configuration(proxyBeanMethods = false)
	static class UserProviders {

		@Bean
		@Primary
		AwsCredentialsProvider primaryCredentials() {
			return StaticCredentialsProvider
				.create(AwsBasicCredentials.create("primary-test-key", "primary-test-secret"));
		}

		@Bean
		AwsCredentialsProvider otherCredentials() {
			return StaticCredentialsProvider.create(AwsBasicCredentials.create("other-test-key", "other-test-secret"));
		}

		@Bean
		AwsRegionProvider applicationRegion() {
			return () -> Region.US_WEST_2;
		}

	}

}
