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

package verification;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.sigv4.AwsSigV4McpRequestCustomizer;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;

import static org.assertj.core.api.Assertions.assertThat;

class PublishedCoreTests {

	@Test
	void publishedCoreSignsAndDeliversUtf8WithoutSpringAiOrBoot() throws Exception {
		var authorization = new AtomicReference<String>();
		var receivedBody = new AtomicReference<String>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/mcp", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			exchange.sendResponseHeaders(202, -1);
			exchange.close();
		});
		server.start();
		try {
			URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
			String body = "{\"message\":\"안녕하세요\"}";
			var signer = new AwsSigV4McpRequestCustomizer(() -> AwsBasicCredentials.create("test", "test-secret"),
					Region.US_EAST_1, "bedrock-agentcore", endpoint);
			var builder = HttpRequest.newBuilder(endpoint)
				.timeout(Duration.ofSeconds(5))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
			HttpRequest.Builder signed = Mono
				.from(signer.customize(builder, "POST", endpoint, body, McpTransportContext.EMPTY))
				.block(Duration.ofSeconds(5));
			assertThat(signed).isNotNull();
			var response = HttpClient.newHttpClient().send(signed.build(), HttpResponse.BodyHandlers.discarding());
			assertThat(response.statusCode()).isEqualTo(202);
			assertThat(authorization.get()).contains("AWS4-HMAC-SHA256", "/us-east-1/bedrock-agentcore/aws4_request");
			assertThat(receivedBody.get()).isEqualTo(body);
		}
		finally {
			server.stop(0);
		}
	}

}
