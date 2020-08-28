/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.crt;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphabetic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static software.amazon.awssdk.http.HttpTestUtils.createProvider;
import static software.amazon.awssdk.http.crt.CrtHttpClientTestUtils.createRequest;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import software.amazon.awssdk.crt.CrtResource;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.http.RecordingNetworkTrafficListener;
import software.amazon.awssdk.http.RecordingResponseHandler;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.utils.Logger;

public class AwsCrtAsyncHttpClientWireMockTest {
    private static final Logger log = Logger.loggerFor(AwsCrtAsyncHttpClientWireMockTest.class);
    private final RecordingNetworkTrafficListener wiremockTrafficListener = new RecordingNetworkTrafficListener();

    @Rule
    public WireMockRule mockServer = new WireMockRule(wireMockConfig()
                                                          .dynamicPort()
                                                          .dynamicHttpsPort()
                                                          .networkTrafficListener(wiremockTrafficListener));

    @BeforeClass
    public static void setup() {
        System.setProperty("aws.crt.debugnative", "true");
    }

    @Before
    public void methodSetup() {
        wiremockTrafficListener.reset();
    }

    @After
    public void tearDown() {
        // Verify there is no resource leak.
        CrtResource.waitForNoResources();
    }

    @Test
    public void closeClient_reuse_throwException() throws Exception {
        SdkAsyncHttpClient client = AwsCrtAsyncHttpClient.create();

        client.close();
        assertThatThrownBy(() -> makeSimpleRequest(client)).hasMessageContaining("is closed");
    }

    @Test
    public void sharedEventLoopGroup_closeOneClient_shouldNotAffectOtherClients() throws Exception {
        try (SdkAsyncHttpClient client = AwsCrtAsyncHttpClient.create()) {
            makeSimpleRequest(client);
        }

        CrtResource.collectNativeResources(s -> log.error(() -> s));

        try (SdkAsyncHttpClient anotherClient = AwsCrtAsyncHttpClient.create()) {
            makeSimpleRequest(anotherClient);
        }
    }

    @Test
    public void sharedEventLoopGroup() throws Exception {
        SdkAsyncHttpClient client = AwsCrtAsyncHttpClient.create();
        SdkAsyncHttpClient anotherClient = AwsCrtAsyncHttpClient.create();

        makeSimpleRequest(client);
        makeSimpleRequest(anotherClient);
        client.close();
        anotherClient.close();
    }

    @Test
    public void customizedEventLoopGroup_closeClient_shouldNotCloseUnderlyingEventLoop() throws Exception {

        SdkEventLoopGroup sdkEventLoopGroup = SdkEventLoopGroup.create(new EventLoopGroup(2));
        CompletableFuture<Void> shutdownCompleteFuture = sdkEventLoopGroup.eventLoopGroup().getShutdownCompleteFuture();
        try (SdkAsyncHttpClient client = AwsCrtAsyncHttpClient.builder().eventLoopGroup(sdkEventLoopGroup)
                                                                                                .build()) {
            makeSimpleRequest(client);
        }
        assertThat(shutdownCompleteFuture).isNotDone();

        try (SdkAsyncHttpClient anotherClient = AwsCrtAsyncHttpClient.builder()
                                                                     .eventLoopGroup(sdkEventLoopGroup)
                                                                     .build()) {
            makeSimpleRequest(anotherClient);
        }

        assertThat(shutdownCompleteFuture).isNotDone();
        sdkEventLoopGroup.eventLoopGroup().close();

        shutdownCompleteFuture.get(5, TimeUnit.SECONDS);
        assertThat(shutdownCompleteFuture).isDone();
    }

    @Test
    public void customizedEventLoopGroupBuilder_closeClient_shouldCloseUnderlyingEventLoop() throws Exception {

        SdkAsyncHttpClient client = AwsCrtAsyncHttpClient.builder()
                                                         .eventLoopGroupBuilder(SdkEventLoopGroup.builder().numberOfThreads(3))
                                                         .build();
        makeSimpleRequest(client);
        client.close();
    }

    /**
     * Make a simple async request and wait for it to finish.
     *
     * @param client Client to make request with.
     */
    private void makeSimpleRequest(SdkAsyncHttpClient client) throws Exception {
        String body = randomAlphabetic(10);
        URI uri = URI.create("http://127.0.0.1:" + mockServer.port());
        stubFor(any(urlPathEqualTo("/")).willReturn(aResponse().withBody(body)));
        SdkHttpRequest request = createRequest(uri);
        RecordingResponseHandler recorder = new RecordingResponseHandler();
        client.execute(AsyncExecuteRequest.builder().request(request).requestContentPublisher(createProvider("")).responseHandler(recorder).build());
        recorder.completeFuture().get(5, TimeUnit.SECONDS);
    }
}
