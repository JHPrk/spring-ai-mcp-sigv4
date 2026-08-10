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

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;

/** Detects Spring AI releases that do not collect HTTP request-customizer beans. */
final class OnLegacyMcpHttpClientIntegrationCondition extends SpringBootCondition {

	private static final String AUTO_CONFIGURATION_CLASS = "org.springframework.ai.mcp.client.httpclient.autoconfigure."
			+ "StreamableHttpHttpClientTransportAutoConfiguration";

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		ConditionMessage.Builder message = ConditionMessage.forCondition("Spring AI MCP HTTP integration");
		ClassLoader classLoader = context.getClassLoader();
		if (!ClassUtils.isPresent(AUTO_CONFIGURATION_CLASS, classLoader)) {
			return ConditionOutcome.noMatch(message.didNotFind("HTTP client auto-configuration").atAll());
		}
		try {
			Class<?> autoConfiguration = ClassUtils.forName(AUTO_CONFIGURATION_CLASS, classLoader);
			Method factoryMethod = Arrays.stream(autoConfiguration.getDeclaredMethods())
				.filter(method -> method.getName().equals("streamableHttpHttpClientTransports"))
				.findFirst()
				.orElseThrow();
			boolean nativeRequestCustomizerSupport = Arrays.stream(factoryMethod.getGenericParameterTypes())
				.map(Type::getTypeName)
				.anyMatch(name -> name.contains(McpAsyncHttpClientRequestCustomizer.class.getName())
						|| name.contains(McpSyncHttpClientRequestCustomizer.class.getName()));
			if (nativeRequestCustomizerSupport) {
				return ConditionOutcome.noMatch(message.found("native request-customizer collection").atAll());
			}
			return ConditionOutcome.match(message.didNotFind("native request-customizer collection").atAll());
		}
		catch (ReflectiveOperationException ex) {
			return ConditionOutcome.noMatch(message.because("could not inspect Spring AI HTTP auto-configuration"));
		}
	}

}
