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

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;

/**
 * Adapts JDK HTTP request metadata to AWS SDK metadata and applies signed headers back to
 * the JDK request builder.
 *
 * <p>
 * The JDK generates the restricted {@code Host} and {@code Content-Length} headers during
 * transmission, so they are not set directly on the builder.
 * </p>
 */
final class AwsV4HttpRequestAdapter {

	/**
	 * Converts an HTTP method, URI, and headers without changing their values.
	 * @param method HTTP method
	 * @param uri target URI including path and query
	 * @param headers current request headers
	 * @return request metadata used by the AWS signer
	 */
	SdkHttpRequest adapt(String method, URI uri, Map<String, List<String>> headers) {
		return SdkHttpRequest.builder().uri(uri).method(SdkHttpMethod.fromValue(method)).headers(headers).build();
	}

	/**
	 * Applies only the authentication and payload hash headers required by the signed
	 * request. Other request headers are already present on the original builder and must
	 * not be rewritten.
	 * @param builder original JDK request builder
	 * @param signedHeaders headers returned by the AWS signer
	 * @return the same request builder
	 */
	HttpRequest.Builder applyRequiredHeaders(HttpRequest.Builder builder, Map<String, List<String>> signedHeaders) {
		signedHeaders.forEach((name, values) -> {
			if (AwsSigV4McpRequestCustomizer.SIGV4_OWNED_HEADERS.contains(name.toLowerCase(Locale.ROOT))
					&& !values.isEmpty()) {
				builder.setHeader(name, values.get(0));
			}
		});
		return builder;
	}

}
