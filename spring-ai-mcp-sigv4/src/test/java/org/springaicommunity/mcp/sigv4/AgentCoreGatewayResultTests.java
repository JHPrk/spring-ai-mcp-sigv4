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

package org.springaicommunity.mcp.sigv4;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCoreGatewayResultTests {

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void matchesJsonTextRegardlessOfExplanationOrder(boolean explanationFirst) {
		var explanation = McpSchema.TextContent.builder("Completed successfully").build();
		var json = McpSchema.TextContent.builder("{\"ok\":true}").build();
		List<McpSchema.Content> content = explanationFirst ? List.of(explanation, json) : List.of(json, explanation);
		var result = McpSchema.CallToolResult.builder(content).build();
		assertThatCode(() -> AgentCoreGatewaySigV4IT.assertExpectedResult(result, "{\"ok\":true}"))
			.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "not-json", "[1]", "null", "{broken" })
	void ignoresNonObjectTextBeforeMatchingJson(String text) {
		var result = McpSchema.CallToolResult
			.builder(List.of(McpSchema.TextContent.builder(text).build(),
					McpSchema.TextContent.builder("{\"ok\":true}").build()))
			.build();
		assertThatCode(() -> AgentCoreGatewaySigV4IT.assertExpectedResult(result, "{\"ok\":true}"))
			.doesNotThrowAnyException();
	}

	@Test
	void stillRejectsAResponseWithoutMatchingJson() {
		var result = McpSchema.CallToolResult
			.builder(List.of(McpSchema.TextContent.builder("private response text").build(),
					McpSchema.TextContent.builder("{\"ok\":false}").build()))
			.build();
		assertThatThrownBy(() -> AgentCoreGatewaySigV4IT.assertExpectedResult(result, "{\"ok\":true}"))
			.isInstanceOf(AssertionError.class)
			.hasMessageContaining("tool response must match the configured expected JSON")
			.hasMessageNotContaining("private response text")
			.hasNoCause();
	}

	@Test
	void matchesStructuredContentWithNonJsonText() {
		var result = McpSchema.CallToolResult
			.builder(List.of(McpSchema.TextContent.builder("Completed successfully").build()))
			.structuredContent(Map.of("ok", true))
			.build();
		assertThatCode(() -> AgentCoreGatewaySigV4IT.assertExpectedResult(result, "{\"ok\":true}"))
			.doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "private invalid expected JSON", "[1]" })
	void stillRejectsInvalidExpectedJsonWithoutExposingIt(String expectedJson) {
		var result = McpSchema.CallToolResult.builder(List.of(McpSchema.TextContent.builder("{\"ok\":true}").build()))
			.build();
		assertThatThrownBy(() -> AgentCoreGatewaySigV4IT.assertExpectedResult(result, expectedJson))
			.isInstanceOf(AssertionError.class)
			.hasMessageNotContaining(expectedJson)
			.hasNoCause();
	}

}
