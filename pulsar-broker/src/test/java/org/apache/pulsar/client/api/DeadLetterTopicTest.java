/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pulsar.client.util.RetryMessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class DeadLetterTopicTest extends ProducerConsumerBase {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterTopicTest.class);

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        super.internalSetup();
        super.producerBaseSetup();
    }

    @AfterMethod(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testDeadLetterTopic() throws Exception {
        final String topic = "persistent://my-property/my-ns/dead-letter-topic";

        final int maxRedeliveryCount = 2;

        final int sendMessages = 100;

        Consumer<byte[]> consumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic(topic)
                .subscriptionName("my-subscription")
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(1, TimeUnit.SECONDS)
                .deadLetterPolicy(DeadLetterPolicy.builder().maxRedeliverCount(maxRedeliveryCount).build())
                .receiverQueueSize(100)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        PulsarClient newPulsarClient = newPulsarClient(lookupUrl.toString(), 0);// Creates new client connection
        Consumer<byte[]> deadLetterConsumer = newPulsarClient.newConsumer(Schema.BYTES)
                .topic("persistent://my-property/my-ns/dead-letter-topic-my-subscription-DLQ")
                .subscriptionName("my-subscription")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        Producer<byte[]> producer = pulsarClient.newProducer(Schema.BYTES)
                .topic(topic)
                .create();

        for (int i = 0; i < sendMessages; i++) {
            producer.send(String.format("Hello Pulsar [%d]", i).getBytes());
        }

        producer.close();

        int totalReceived = 0;
        do {
            Message<byte[]> message = consumer.receive();
            log.info("consumer received message : {} {}", message.getMessageId(), new String(message.getData()));
            totalReceived++;
        } while (totalReceived < sendMessages * (maxRedeliveryCount + 1));

        int totalInDeadLetter = 0;
        do {
            Message message = deadLetterConsumer.receive();
            log.info("dead letter consumer received message : {} {}", message.getMessageId(), new String(message.getData()));
            deadLetterConsumer.acknowledge(message);
            totalInDeadLetter++;
        } while (totalInDeadLetter < sendMessages);

        deadLetterConsumer.close();
        consumer.close();

        Consumer<byte[]> checkConsumer = this.pulsarClient.newConsumer(Schema.BYTES)
                .topic(topic)
                .subscriptionName("my-subscription")
                .subscriptionType(SubscriptionType.Shared)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        Message<byte[]> checkMessage = checkConsumer.receive(3, TimeUnit.SECONDS);
        if (checkMessage != null) {
            log.info("check consumer received message : {} {}", checkMessage.getMessageId(), new String(checkMessage.getData()));
        }
        assertNull(checkMessage);

        checkConsumer.close();
        newPulsarClient.close();
    }

    @Test(timeOut = 20000)
    public void testDeadLetterTopicHasOriginalInfo() throws Exception {
        final String topic = "persistent://my-property/my-ns/dead-letter-topic";

        final int maxRedeliveryCount = 1;
        final int sendMessages = 10;

        Consumer<byte[]> consumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic(topic)
                .subscriptionName("my-subscription")
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(1, TimeUnit.SECONDS)
                .deadLetterPolicy(DeadLetterPolicy.builder().maxRedeliverCount(maxRedeliveryCount).build())
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        PulsarClient newPulsarClient = newPulsarClient(lookupUrl.toString(), 0);// Creates new client connection
        Consumer<byte[]> deadLetterConsumer = newPulsarClient.newConsumer(Schema.BYTES)
                .topic("persistent://my-property/my-ns/dead-letter-topic-my-subscription-DLQ")
                .subscriptionName("my-subscription")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        Producer<byte[]> producer = pulsarClient.newProducer(Schema.BYTES)
                .topic(topic)
                .create();
        Set<String> messageIds = new HashSet<>();
        for (int i = 0; i < sendMessages; i++) {
            MessageId messageId = producer.send(String.format("Hello Pulsar [%d]", i).getBytes());
            messageIds.add(messageId.toString());
        }
        producer.close();

        int totalReceived = 0;
        do {
            consumer.receive();
            totalReceived++;
        } while (totalReceived < sendMessages * (maxRedeliveryCount + 1));

        int totalInDeadLetter = 0;
        do {
            Message<byte[]> message = deadLetterConsumer.receive();
            //Original info should exists
            assertEquals(message.getProperties().get(RetryMessageUtil.SYSTEM_PROPERTY_REAL_TOPIC), topic);
            assertTrue(messageIds.contains(message.getProperties().get(RetryMessageUtil.SYSTEM_PROPERTY_ORIGIN_MESSAGE_ID)));
            deadLetterConsumer.acknowledge(message);
            totalInDeadLetter++;
        } while (totalInDeadLetter < sendMessages);
        assertEquals(totalInDeadLetter, sendMessages);
        deadLetterConsumer.close();
        consumer.close();
        newPulsarClient.close();
    }

    @Test(timeOut = 30000)
    public void testDLQDisabledForKeySharedSubtype() throws Exception {
        final String topic = "persistent://my-property/my-ns/dead-letter-topic";

        final int maxRedeliveryCount = 2;

        final int sendMessages = 100;

        Consumer<byte[]> consumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic(topic)
                .subscriptionName("my-subscription")
                .subscriptionType(SubscriptionType.Key_Shared)
                .keySharedPolicy(KeySharedPolicy.autoSplitHashRange())
                .ackTimeout(1, TimeUnit.SECONDS)
                .deadLetterPolicy(DeadLetterPolicy.builder().maxRedeliverCount(maxRedeliveryCount).build())
                .receiverQueueSize(100)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        PulsarClient newPulsarClient = newPulsarClient(lookupUrl.toString(), 0);
        // Creates new client connection
        Consumer<byte[]> deadLetterConsumer = newPulsarClient.newConsumer(Schema.BYTES)
                .topic("persistent://my-property/my-ns/dead-letter-topic-my-subscription-DLQ")
                .subscriptionName("my-subscription")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        Producer<byte[]> producer = pulsarClient.newProducer(Schema.BYTES)
                .topic(topic)
                .create();

        for (int i = 0; i < sendMessages; i++) {
            producer.send(String.format("Hello Pulsar [%d]", i).getBytes());
        }

        producer.close();

        int totalReceived = 0;
        Message<byte[]> message;
        do {
            message = consumer.receive();
            log.info("consumer received message : {} {}", message.getMessageId(), new String(message.getData()));
            assertNotNull(message);
            totalReceived++;
        } while (totalReceived < sendMessages * (maxRedeliveryCount + 1));
        // make sure message not acked sent to retry topic.
        assertEquals(totalReceived, sendMessages * (maxRedeliveryCount + 1));

        // make sure no message sent to dead letter topic.
        Message dlqMessage = deadLetterConsumer.receive(5, TimeUnit.SECONDS);
        assertNull(dlqMessage);

        deadLetterConsumer.close();
        consumer.close();
        newPulsarClient.close();
    }

    @Test(timeOut = 30000)
    public void testDuplicatedMessageSendToDeadLetterTopic() throws Exception {
        final String topic = "persistent://my-property/my-ns/dead-letter-topic-DuplicatedMessage";
        final int maxRedeliveryCount = 1;
        final int messageCount = 10;
        final int consumerCount = 3;
        //1 start 3 parallel consumers
        List<Consumer<String>> consumers = new ArrayList<>();
        final AtomicInteger totalReceived = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(consumerCount);
        for (int i = 0; i < consumerCount; i++) {
            executor.execute(() -> {
                try {
                    Consumer<String> consumer = pulsarClient.newConsumer(Schema.STRING)
                            .topic(topic)
                            .subscriptionName("my-subscription-DuplicatedMessage")
                            .subscriptionType(SubscriptionType.Shared)
                            .ackTimeout(1001, TimeUnit.MILLISECONDS)
                            .deadLetterPolicy(DeadLetterPolicy.builder().maxRedeliverCount(maxRedeliveryCount).deadLetterTopic(topic + "-DLQ").build())
                            .negativeAckRedeliveryDelay(1001, TimeUnit.MILLISECONDS)
                            .receiverQueueSize(100)
                            .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                            .messageListener((MessageListener<String>) (consumer1, msg) -> {
                                totalReceived.getAndIncrement();
                                //never ack
                            })
                            .subscribe();
                    consumers.add(consumer);
                } catch (PulsarClientException e) {
                    fail();
                }
            });
        }

        //2 send messages
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING).topic(topic).create();
        for (int i = 0; i < messageCount; i++) {
            producer.send(String.format("Message [%d]", i));
        }

        //3 start a DLQ consumer
        Consumer<String> deadLetterConsumer = pulsarClient.newConsumer(Schema.STRING)
                .topic(topic + "-DLQ")
                .subscriptionName("my-subscription-DuplicatedMessage-DLQ")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();
        int totalInDeadLetter = 0;
        while (true) {
            Message<String> message = deadLetterConsumer.receive(10, TimeUnit.SECONDS);
            if (message == null) {
                break;
            }
            deadLetterConsumer.acknowledge(message);
            totalInDeadLetter++;
        }

        //4 The number of messages that consumers can consume should be equal to messageCount * (maxRedeliveryCount + 1)
        assertEquals(totalReceived.get(), messageCount * (maxRedeliveryCount + 1));

        //5 The message in DLQ should be equal to messageCount
        assertEquals(totalInDeadLetter, messageCount);

        //6 clean up
        executor.shutdownNow();
        producer.close();
        deadLetterConsumer.close();
        for (Consumer<String> consumer : consumers) {
            consumer.close();
        }
    }

    /**
     * The test is disabled {@link https://github.com/apache/pulsar/issues/2647}.
     * @throws Exception
     */
    @Test(enabled = false)
    public void testDeadLetterTopicWithMultiTopic() throws Exception {
        final String topic1 = "persistent://my-property/my-ns/dead-letter-topic-1";
        final String topic2 = "persistent://my-property/my-ns/dead-letter-topic-2";

        final int maxRedeliveryCount = 2;

        int sendMessages = 100;

        // subscribe to the original topics before publish
        Consumer<byte[]> consumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic(topic1, topic2)
                .subscriptionName("my-subscription")
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(1, TimeUnit.SECONDS)
                .deadLetterPolicy(DeadLetterPolicy.builder().maxRedeliverCount(maxRedeliveryCount).build())
                .receiverQueueSize(100)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        // subscribe to the DLQ topics before consuming original topics
        Consumer<byte[]> deadLetterConsumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic("persistent://my-property/my-ns/dead-letter-topic-1-my-subscription-DLQ", "persistent://my-property/my-ns/dead-letter-topic-2-my-subscription-DLQ")
                .subscriptionName("my-subscription")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        Producer<byte[]> producer1 = pulsarClient.newProducer(Schema.BYTES)
                .topic(topic1)
                .create();

        Producer<byte[]> producer2 = pulsarClient.newProducer(Schema.BYTES)
                .topic(topic2)
                .create();

        for (int i = 0; i < sendMessages; i++) {
            producer1.send(String.format("Hello Pulsar [%d]", i).getBytes());
            producer2.send(String.format("Hello Pulsar [%d]", i).getBytes());
        }

        sendMessages = sendMessages * 2;

        producer1.close();
        producer2.close();

        int totalReceived = 0;
        do {
            Message<byte[]> message = consumer.receive();
            log.info("consumer received message : {} {} - total = {}",
                message.getMessageId(), new String(message.getData()), ++totalReceived);
        } while (totalReceived < sendMessages * (maxRedeliveryCount + 1));

        int totalInDeadLetter = 0;
        do {
            Message message = deadLetterConsumer.receive();
            log.info("dead letter consumer received message : {} {}", message.getMessageId(), new String(message.getData()));
            deadLetterConsumer.acknowledge(message);
            totalInDeadLetter++;
        } while (totalInDeadLetter < sendMessages);

        deadLetterConsumer.close();
        consumer.close();

        Consumer<byte[]> checkConsumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic(topic1, topic2)
                .subscriptionName("my-subscription")
                .subscriptionType(SubscriptionType.Shared)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();

        Message<byte[]> checkMessage = checkConsumer.receive(3, TimeUnit.SECONDS);
        if (checkMessage != null) {
            log.info("check consumer received message : {} {}", checkMessage.getMessageId(), new String(checkMessage.getData()));
        }
        assertNull(checkMessage);

        checkConsumer.close();
    }

    @Test
    public void testDeadLetterTopicByCustomTopicName() throws Exception {
        final String topic = "persistent://my-property/my-ns/dead-letter-topic";
        final int maxRedeliveryCount = 2;
        final int sendMessages = 100;

        // subscribe before publish
        Consumer<byte[]> consumer = pulsarClient.newConsumer(Schema.BYTES)
                .topic(topic)
                .subscriptionName("my-subscription")
                .subscriptionType(SubscriptionType.Shared)
                .ackTimeout(1, TimeUnit.SECONDS)
                .receiverQueueSize(100)
                .deadLetterPolicy(DeadLetterPolicy.builder()
                        .maxRedeliverCount(maxRedeliveryCount)
                        .deadLetterTopic("persistent://my-property/my-ns/dead-letter-custom-topic-my-subscription-custom-DLQ")
                        .build())
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();
        PulsarClient newPulsarClient = newPulsarClient(lookupUrl.toString(), 0);// Creates new client connection
        Consumer<byte[]> deadLetterConsumer = newPulsarClient.newConsumer(Schema.BYTES)
                .topic("persistent://my-property/my-ns/dead-letter-custom-topic-my-subscription-custom-DLQ")
                .subscriptionName("my-subscription")
                .subscribe();

        Producer<byte[]> producer = pulsarClient.newProducer(Schema.BYTES)
                .topic(topic)
                .create();
        for (int i = 0; i < sendMessages; i++) {
            producer.send(String.format("Hello Pulsar [%d]", i).getBytes());
        }
        producer.close();

        int totalReceived = 0;
        do {
            Message<byte[]> message = consumer.receive();
            log.info("consumer received message : {} {}", message.getMessageId(), new String(message.getData()));
            totalReceived++;
        } while (totalReceived < sendMessages * (maxRedeliveryCount + 1));
        int totalInDeadLetter = 0;
        do {
            Message message = deadLetterConsumer.receive();
            log.info("dead letter consumer received message : {} {}", message.getMessageId(), new String(message.getData()));
            deadLetterConsumer.acknowledge(message);
            totalInDeadLetter++;
        } while (totalInDeadLetter < sendMessages);
        deadLetterConsumer.close();
        consumer.close();
        PulsarClient newPulsarClient1 = newPulsarClient(lookupUrl.toString(), 0);// Creates new client connection
        Consumer<byte[]> checkConsumer = newPulsarClient1.newConsumer(Schema.BYTES)
                .topic(topic)
                .subscriptionName("my-subscription")
                .subscriptionType(SubscriptionType.Shared)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscribe();
        Message<byte[]> checkMessage = checkConsumer.receive(3, TimeUnit.SECONDS);
        if (checkMessage != null) {
            log.info("check consumer received message : {} {}", checkMessage.getMessageId(), new String(checkMessage.getData()));
        }
        assertNull(checkMessage);
        newPulsarClient.close();
        newPulsarClient1.close();
        checkConsumer.close();
    }

    /**
     * issue https://github.com/apache/pulsar/issues/3077
     */
    @Test(timeOut = 200000)
    public void testDeadLetterWithoutConsumerReceiveImmediately() throws PulsarClientException, InterruptedException {
        final String topic = "persistent://my-property/my-ns/dead-letter-topic-without-consumer-receive-immediately";

        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(topic)
                .subscriptionType(SubscriptionType.Shared)
                .subscriptionName("my-subscription")
                .ackTimeout(1, TimeUnit.SECONDS)
                .deadLetterPolicy(DeadLetterPolicy.builder().maxRedeliverCount(1).build())
                .subscribe();

        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                .create();

        producer.send(("a message").getBytes());

        // Wait a while, message should not be send to DLQ
        Thread.sleep(5000L);

        Message<byte[]> msg = consumer.receive(1, TimeUnit.SECONDS);
        assertNotNull(msg);
    }
}
