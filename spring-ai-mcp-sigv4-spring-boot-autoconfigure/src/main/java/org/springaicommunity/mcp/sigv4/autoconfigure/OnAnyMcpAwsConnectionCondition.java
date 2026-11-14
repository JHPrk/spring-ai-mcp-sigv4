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

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.Assert;

/**
 * Match configured AWS connections using the binder rather than a collection property
 * presence check. Reject the old namespace to prevent silently disabled signing.
 *
 * @since 0.1.0
 */
final class OnAnyMcpAwsConnectionCondition extends SpringBootCondition {

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Binder binder = Binder.get(context.getEnvironment());
		boolean legacyConfigured = binder
			.bind("spring.ai.mcp.client.authorization.aws.connections",
					Bindable.mapOf(String.class, McpAwsProperties.Connection.class))
			.isBound();
		Assert.state(!legacyConfigured,
				"Move AWS signing settings from spring.ai.mcp.client.authorization.aws.connections to mcp.sigv4.connections");
		Map<String, McpAwsProperties.Connection> connections = binder
			.bind(McpAwsProperties.CONFIG_PREFIX + ".connections",
					Bindable.mapOf(String.class, McpAwsProperties.Connection.class))
			.orElse(Map.of());
		ConditionMessage.Builder message = ConditionMessage.forCondition("MCP AWS SigV4 connection");
		if (!connections.isEmpty()) {
			return ConditionOutcome.match(message.found("AWS connection").items(connections.keySet()));
		}
		return ConditionOutcome.noMatch(message.didNotFind("AWS connection").atAll());
	}

}
