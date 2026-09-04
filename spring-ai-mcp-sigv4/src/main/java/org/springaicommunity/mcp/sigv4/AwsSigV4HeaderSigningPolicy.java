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

/**
 * Determines which existing HTTP headers are eligible for canonical SigV4 signing. An
 * ineligible header remains on the wire but is not integrity-protected by SigV4. The AWS
 * signer may independently exclude eligible headers and always controls its generated
 * authentication headers and required host metadata.
 *
 * <p>
 * Implementations must be thread-safe and treat header names case-insensitively. The
 * request customizer supplies lower-case names using {@link java.util.Locale#ROOT}.
 * Implementations must not depend on mutable per-request state.
 * </p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface AwsSigV4HeaderSigningPolicy {

	/**
	 * Determines eligibility for signing, not whether the header is sent.
	 * @param headerName HTTP header name, compared case-insensitively
	 * @return whether the header is eligible for canonical SigV4 signing
	 * @since 0.1.0
	 */
	boolean shouldSign(String headerName);

}
