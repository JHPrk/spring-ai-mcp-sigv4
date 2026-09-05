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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AwsSigV4HeaderSigningPoliciesTests {

	@ParameterizedTest
	@ValueSource(strings = { "traceparent", "tracestate", "baggage", "b3", "x-b3-traceid", "x-b3-spanid",
			"x-b3-parentspanid", "x-b3-sampled", "x-b3-flags", "x-amzn-trace-id" })
	void excludesPropagationCaseInsensitively(String name) {
		assertThat(AwsSigV4HeaderSigningPolicies.defaultPolicy().shouldSign(name)).isFalse();
		assertThat(AwsSigV4HeaderSigningPolicies.defaultPolicy().shouldSign(name.toUpperCase(Locale.ROOT))).isFalse();
		assertThat(AwsSigV4HeaderSigningPolicies.all().shouldSign(name)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Content-Type", "Mcp-Protocol-Version", "Mcp-Session-Id", "Last-Event-Id",
			"X-Custom-Header", "user-agent", "connection", "expect", "transfer-encoding", "x-forwarded-for" })
	void leavesOtherHeadersEligibleForAwsSigner(String name) {
		assertThat(AwsSigV4HeaderSigningPolicies.defaultPolicy().shouldSign(name)).isTrue();
	}

	@Test
	void excludingCopiesNormalizesAndDeduplicatesExactNames() {
		List<String> names = new ArrayList<>(List.of(" X-Company-Trace ", "x-company-trace"));
		AwsSigV4HeaderSigningPolicy policy = AwsSigV4HeaderSigningPolicies.excluding(names);
		names.clear();
		assertThat(policy.shouldSign("X-COMPANY-TRACE")).isFalse();
		assertThat(policy.shouldSign("x-company-trace-other")).isTrue();
		assertThat(policy.shouldSign("traceparent")).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "\t" })
	void rejectsBlankNames(String name) {
		assertThatIllegalArgumentException().isThrownBy(() -> AwsSigV4HeaderSigningPolicies.excluding(List.of(name)));
	}

}
