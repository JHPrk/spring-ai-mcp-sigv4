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

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.util.Assert;

/**
 * Immutable, thread-safe header signing policies with exact, case-insensitive matching.
 * These policies filter signing input only; they never remove wire headers.
 *
 * @since 0.1.0
 */
public final class AwsSigV4HeaderSigningPolicies {

	private static final AwsSigV4HeaderSigningPolicy ALL = name -> true;

	private static final AwsSigV4HeaderSigningPolicy DEFAULT = excluding(Set.of("traceparent", "tracestate", "baggage",
			"b3", "x-b3-traceid", "x-b3-spanid", "x-b3-parentspanid", "x-b3-sampled", "x-b3-flags", "x-amzn-trace-id"));

	private AwsSigV4HeaderSigningPolicies() {
	}

	/**
	 * Signs stable headers by default, excluding W3C trace context and baggage, B3
	 * single/multi headers, and X-Ray propagation. Other AWS canonical-header exclusions
	 * remain the responsibility of the AWS signer; no internal AWS API is used.
	 * @return shared default policy
	 * @since 0.1.0
	 */
	public static AwsSigV4HeaderSigningPolicy defaultPolicy() {
		return DEFAULT;
	}

	/**
	 * Restores eligibility of all existing headers at this library's signing boundary.
	 * The AWS signer still applies its own exclusions, including {@code x-amzn-trace-id}.
	 * @return shared policy accepting all headers
	 * @since 0.1.0
	 */
	public static AwsSigV4HeaderSigningPolicy all() {
		return ALL;
	}

	/**
	 * Excludes only the given exact header names, without adding default exclusions.
	 * Names are trimmed, normalized with {@link Locale#ROOT}, deduplicated, and copied.
	 * Excluding a header removes its SigV4 integrity protection. Keep stable business and
	 * MCP headers eligible whenever practical.
	 * @param headerNames non-null collection of non-blank header names
	 * @return immutable policy excluding the specified names
	 * @throws IllegalArgumentException if the collection or a name is null or blank
	 * @since 0.1.0
	 */
	public static AwsSigV4HeaderSigningPolicy excluding(Collection<String> headerNames) {
		Assert.notNull(headerNames, "headerNames must not be null");
		Set<String> normalized = new HashSet<>();
		for (String name : headerNames) {
			Assert.hasText(name, "header names must not be blank");
			normalized.add(name.trim().toLowerCase(Locale.ROOT));
		}
		Set<String> excluded = Set.copyOf(normalized);
		return name -> !excluded.contains(name.toLowerCase(Locale.ROOT));
	}

}
