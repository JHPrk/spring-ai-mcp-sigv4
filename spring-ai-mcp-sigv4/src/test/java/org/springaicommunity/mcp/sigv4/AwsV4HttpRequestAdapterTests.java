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
import java.util.Map;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class AwsV4HttpRequestAdapterTests {

	private final AwsV4HttpRequestAdapter adapter = new AwsV4HttpRequestAdapter();

	@Test
	void shouldPreserveEncodedPathQueryAndHeaders() {
		URI uri = URI.create("https://example.com/gateway/%ED%95%9C%EA%B8%80/mcp/?q=a%20b&q=c");

		var request = this.adapter.adapt("DELETE", uri,
				Map.of("Content-Type", List.of("application/json"), "X-Multi", List.of("one", "two")));

		assertThat(request.method()).isEqualTo(SdkHttpMethod.DELETE);
		assertThat(request.encodedPath()).isEqualTo("/gateway/%ED%95%9C%EA%B8%80/mcp/");
		assertThat(request.rawQueryParameters()).containsKey("q");
		assertThat(request.headers()).containsEntry("Content-Type", List.of("application/json"))
			.containsEntry("X-Multi", List.of("one", "two"));
	}

	@Test
	void shouldApplyOnlyRequiredSigV4Headers() {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://example.com/mcp"))
			.header("X-App", "original")
			.GET();

		this.adapter.applyRequiredHeaders(builder,
				Map.of("Authorization", List.of("signed"), "X-Amz-Date", List.of("20260810T000000Z"),
						"X-Amz-Security-Token", List.of("token"), "X-Amz-Content-Sha256", List.of("payload-hash"),
						"X-App", List.of("rewritten"), "X-Multi", List.of("one", "two"), "Host",
						List.of("example.com")));

		HttpRequest request = builder.build();
		assertThat(request.headers().allValues("Authorization")).containsExactly("signed");
		assertThat(request.headers().firstValue("X-Amz-Date")).contains("20260810T000000Z");
		assertThat(request.headers().firstValue("X-Amz-Security-Token")).contains("token");
		assertThat(request.headers().firstValue("X-Amz-Content-Sha256")).contains("payload-hash");
		assertThat(request.headers().firstValue("X-App")).contains("original");
		assertThat(request.headers().allValues("X-Multi")).isEmpty();
		assertThat(request.headers().firstValue("Host")).isEmpty();
	}

}
