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

package software.amazon.awssdk.benchmark;

import static java.util.Collections.singletonMap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.utils.Either;

@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
public class HttpClientComparison {
    private static final PutItemRequest PUT_ITEM_REQUEST = PutItemRequest.builder()
                                                                         .tableName("millem-throughput")
                                                                         .item(singletonMap("key", AttributeValue.builder()
                                                                                                                 .s("value")
                                                                                                                 .build()))
                                                                         .build();

    @Benchmark
    @Threads(Threads.MAX)
    public void callsCpuCount(Blackhole blackhole, BenchmarkState state) {
        call(blackhole, state);
    }

    @Benchmark
    @Threads(1)
    public void calls1(Blackhole blackhole, BenchmarkState state) {
        call(blackhole, state);
    }

    @Benchmark
    @Threads(10)
    public void calls10(Blackhole blackhole, BenchmarkState state) {
        call(blackhole, state);
    }

    @Benchmark
    @Threads(100)
    public void calls100(Blackhole blackhole, BenchmarkState state) {
        call(blackhole, state);
    }

    private void call(Blackhole blackhole, BenchmarkState state) {
        state.clientInstance.apply(sync -> blackhole.consume(sync.putItem(PUT_ITEM_REQUEST)),
                                   async -> blackhole.consume(async.putItem(PUT_ITEM_REQUEST).join()));
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        @Param({"netty", "crt"})
        public String clientName;

        public Either<DynamoDbClient, DynamoDbAsyncClient> clientInstance;

        @Setup(Level.Trial)
        public void setUp() {
            switch (clientName) {
                case "apache":
                    clientInstance = Either.left(DynamoDbClient.builder().httpClientBuilder(ApacheHttpClient.builder().maxConnections(100)).build());
                    break;
                case "urlConnection":
                    clientInstance = Either.left(DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build());
                    break;
                case "netty":
                    clientInstance = Either.right(DynamoDbAsyncClient.builder()
                                                                     .asyncConfiguration(c -> c.advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, Runnable::run))
                                                                     .httpClientBuilder(NettyNioAsyncHttpClient.builder().maxConcurrency(100))
                                                                     .build());
                    break;
                case "crt":
                    clientInstance = Either.right(DynamoDbAsyncClient.builder()
                                                                     .asyncConfiguration(c -> c.advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, Runnable::run))
                                                                     .httpClientBuilder(AwsCrtAsyncHttpClient.builder().maxConcurrency(100))
                                                                     .build());
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    public static void main(String... args) throws Exception {
        Options opt = new OptionsBuilder()
            .include(HttpClientComparison.class.getSimpleName())
            .build();
        new Runner(opt).run();
    }
}
