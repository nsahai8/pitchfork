package com.hotels.service.tracing.zipkintohaystack;

import static java.time.Duration.ofSeconds;
import static java.util.Collections.singletonList;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.SECONDS;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

import com.expedia.open.tracing.Span;
import zipkin2.Endpoint;
import zipkin2.codec.Encoding;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.okhttp3.OkHttpSender;

@Testcontainers
@DirtiesContext
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = {HaystackKafkaForwarderTest.Initializer.class})
class HaystackKafkaForwarderTest {

    @Container
    private static final KafkaContainer kafkaContainer = new KafkaContainer();

    @LocalServerPort
    private int localServerPort;

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        public void initialize(ConfigurableApplicationContext context) {
            var values = TestPropertyValues.of(
                    "pitchfork.forwarders.haystack.kafka.enabled=true",
                    "pitchfork.forwarders.haystack.kafka.bootstrap-servers=" + kafkaContainer.getBootstrapServers()
            );
            values.applyTo(context);
        }
    }

    @Test
     void shouldForwardTracesToKafka() throws Exception {
        String spanId = "2696599e12b2a265";
        String traceId = "3116bae014149aad";
        String parentId = "d6318b5dfa0088fa";
        long timestamp = 1528386023537760L;
        int duration = 17636;
        String localEndpoint = "abc";

        var zipkinSpan = zipkin2.Span.newBuilder()
                .id(spanId)
                .traceId(traceId)
                .parentId(parentId)
                .timestamp(timestamp)
                .duration(duration)
                .localEndpoint(Endpoint.newBuilder().serviceName(localEndpoint).build())
                .build();

        var reporter = setupReporter();
        reporter.report(zipkinSpan);

        // proxy is async, and kafka is async too, so we retry our assertions until they are true
        try (KafkaConsumer<String, byte[]> consumer = setupConsumer()) {
            await().atMost(10, SECONDS).untilAsserted(() -> {
                ConsumerRecords<String, byte[]> records = consumer.poll(ofSeconds(1));

                assertFalse(records.isEmpty());

                Optional<Span> span = deserialize(records.iterator().next().value()); // there's only one element so get first

                assertTrue(span.isPresent());
                assertEquals(span.get().getTraceId(), traceId);
                assertEquals(span.get().getSpanId(), spanId);
                assertEquals(span.get().getParentSpanId(), parentId);
                assertEquals(span.get().getStartTime(), timestamp);
                assertEquals(span.get().getDuration(), duration);
            });
        }
    }

    /**
     * Create consumer and subscribe to spans topic.
     */
    private KafkaConsumer<String, byte[]> setupConsumer() {
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(
                ImmutableMap.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG, "test-group",
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
                ),
                new StringDeserializer(),
                new ByteArrayDeserializer()
        );
        consumer.subscribe(singletonList("proto-spans"));

        return consumer;
    }

    /**
     * Create reporter.
     */
    private AsyncReporter<zipkin2.Span> setupReporter() {
        var sender = OkHttpSender.newBuilder()
                .encoding(Encoding.PROTO3)
                .endpoint("http://localhost:" + localServerPort + "/api/v2/spans")
                .build();
        return AsyncReporter.create(sender);
    }

    private static Optional<Span> deserialize(byte[] data) {
        try {
            return ofNullable(Span.parseFrom(data));
        } catch (Exception e) {
            fail("Failed to deserialise span from data");
            return empty();
        }
    }
}
