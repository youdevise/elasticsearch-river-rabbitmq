/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.river.rabbitmq;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import com.rabbitmq.client.QueueingConsumer;

/**
 *
 */
public class RabbitmqRiver extends AbstractRiverComponent implements River {

    private final Client client;

    private final Address[] rabbitAddresses;
    private final String rabbitUser;
    private final String rabbitPassword;
    private final String rabbitVhost;

    private final String rabbitQueue;
    private final String rabbitExchange;
    private final String rabbitExchangeType;
    private final String rabbitRoutingKey;
    //See http://www.enterpriseintegrationpatterns.com/InvalidMessageChannel.html for more info
    private final String rabbitInvalidMessageChannelQueue;
    private final boolean rabbitExchangeDurable;
    private final boolean rabbitQueueDurable;
    private final boolean rabbitQueueAutoDelete;
    private Map rabbitQueueArgs = null; //extra arguments passed to queue for creation (ha settings for example)

    private final int prefetchCount;

    private final int bulkSize;
    private final TimeValue bulkTimeout;
    private final boolean ordered;
    private final int maxTries;
    private final int minRetryTime;
    private final int maxRetryTime;

    private volatile boolean closed = false;

    private volatile Thread thread;

    private volatile ConnectionFactory connectionFactory;

    @SuppressWarnings({"unchecked"})
    @Inject
    public RabbitmqRiver(RiverName riverName, RiverSettings settings, Client client) {
        super(riverName, settings);
        this.client = client;

        if (settings.settings().containsKey("rabbitmq")) {
            Map<String, Object> rabbitSettings = (Map<String, Object>) settings.settings().get("rabbitmq");

            if (rabbitSettings.containsKey("addresses")) {
                List<Address> addresses = new ArrayList<Address>();
                for(Map<String, Object> address : (List<Map<String, Object>>) rabbitSettings.get("addresses")) {
                    addresses.add( new Address(XContentMapValues.nodeStringValue(address.get("host"), "localhost"),
                            XContentMapValues.nodeIntegerValue(address.get("port"), AMQP.PROTOCOL.PORT)));
                }
                rabbitAddresses = addresses.toArray(new Address[addresses.size()]);
            } else {
                String rabbitHost = XContentMapValues.nodeStringValue(rabbitSettings.get("host"), "localhost");
                int rabbitPort = XContentMapValues.nodeIntegerValue(rabbitSettings.get("port"), AMQP.PROTOCOL.PORT);
                rabbitAddresses = new Address[]{ new Address(rabbitHost, rabbitPort) };
            }

            rabbitUser = XContentMapValues.nodeStringValue(rabbitSettings.get("user"), "guest");
            rabbitPassword = XContentMapValues.nodeStringValue(rabbitSettings.get("pass"), "guest");
            rabbitVhost = XContentMapValues.nodeStringValue(rabbitSettings.get("vhost"), "/");

            rabbitQueue = XContentMapValues.nodeStringValue(rabbitSettings.get("queue"), "elasticsearch");
            rabbitExchange = XContentMapValues.nodeStringValue(rabbitSettings.get("exchange"), "elasticsearch");
            rabbitExchangeType = XContentMapValues.nodeStringValue(rabbitSettings.get("exchange_type"), "direct");
            //See http://www.enterpriseintegrationpatterns.com/InvalidMessageChannel.html for more info
            rabbitInvalidMessageChannelQueue = XContentMapValues.nodeStringValue(rabbitSettings.get("invalid_message_queue"), "elasticsearch_failed");
            rabbitRoutingKey = XContentMapValues.nodeStringValue(rabbitSettings.get("routing_key"), "elasticsearch");
            rabbitExchangeDurable = XContentMapValues.nodeBooleanValue(rabbitSettings.get("exchange_durable"), true);
            rabbitQueueDurable = XContentMapValues.nodeBooleanValue(rabbitSettings.get("queue_durable"), true);
            rabbitQueueAutoDelete = XContentMapValues.nodeBooleanValue(rabbitSettings.get("queue_auto_delete"), false);
            prefetchCount = XContentMapValues.nodeIntegerValue(rabbitSettings.get("prefetch_count"), 100);
            if (rabbitSettings.containsKey("args")) {
                rabbitQueueArgs = (Map<String, Object>) rabbitSettings.get("args");
            }
        } else {
            rabbitAddresses = new Address[]{ new Address("localhost", AMQP.PROTOCOL.PORT) };
            rabbitUser = "guest";
            rabbitPassword = "guest";
            rabbitVhost = "/";

            rabbitQueue = "elasticsearch";
            rabbitQueueAutoDelete = false;
            rabbitQueueDurable = true;
            rabbitExchange = "elasticsearch";
            rabbitExchangeType = "direct";
            rabbitInvalidMessageChannelQueue = "elasticsearch_failed";
            rabbitExchangeDurable = true;
            rabbitRoutingKey = "elasticsearch";
            prefetchCount = 100;
        }

        if (settings.settings().containsKey("index")) {
            Map<String, Object> indexSettings = (Map<String, Object>) settings.settings().get("index");
            bulkSize = XContentMapValues.nodeIntegerValue(indexSettings.get("bulk_size"), 100);
            if (indexSettings.containsKey("bulk_timeout")) {
                bulkTimeout = TimeValue.parseTimeValue(XContentMapValues.nodeStringValue(indexSettings.get("bulk_timeout"), "10ms"), TimeValue.timeValueMillis(10));
            } else {
                bulkTimeout = TimeValue.timeValueMillis(10);
            }
            ordered = XContentMapValues.nodeBooleanValue(indexSettings.get("ordered"), false);
            maxTries = 1 + XContentMapValues.nodeIntegerValue(indexSettings.get("retries"), 9);
            minRetryTime = XContentMapValues.nodeIntegerValue(indexSettings.get("retry_min_wait"), 1);
            maxRetryTime = XContentMapValues.nodeIntegerValue(indexSettings.get("retry_max_wait"), 30);
        } else {
            bulkSize = 100;
            bulkTimeout = TimeValue.timeValueMillis(10);
            ordered = false;
            maxTries = 10;
            minRetryTime = 1;
            maxRetryTime = 30;
        }
    }

    @Override
    public void start() {
        connectionFactory = new ConnectionFactory();
        connectionFactory.setUsername(rabbitUser);
        connectionFactory.setPassword(rabbitPassword);
        connectionFactory.setVirtualHost(rabbitVhost);

        logger.info("creating rabbitmq river, addresses [{}], user [{}], vhost [{}]", rabbitAddresses, connectionFactory.getUsername(), connectionFactory.getVirtualHost());

        thread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "rabbitmq_river").newThread(new Consumer());
        thread.start();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        logger.info("closing rabbitmq river");
        closed = true;
        thread.interrupt();
    }

    private class Consumer implements Runnable {

        private Connection connection;

        private Channel channel;

        @Override
        public void run() {
            while (true) {
                if (closed) {
                    break;
                }
                try {
                    connection = connectionFactory.newConnection(rabbitAddresses);
                    channel = connection.createChannel();
                    channel.basicQos(prefetchCount);
                } catch (Exception e) {
                    if (!closed) {
                        logger.warn("failed to created a connection / channel", e);
                    } else {
                        continue;
                    }
                    cleanup(0, "failed to connect");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e1) {
                        // ignore, if we are closing, we will exit later
                    }
                }

                QueueingConsumer consumer = new QueueingConsumer(channel);
                // define the queue
                try {
                    channel.exchangeDeclare(rabbitExchange/*exchange*/, rabbitExchangeType/*type*/, rabbitExchangeDurable);
                    channel.queueDeclare(rabbitQueue/*queue*/, rabbitQueueDurable/*durable*/, false/*exclusive*/, rabbitQueueAutoDelete/*autoDelete*/, rabbitQueueArgs/*extra args*/);
                    channel.queueBind(rabbitQueue/*queue*/, rabbitExchange/*exchange*/, rabbitRoutingKey/*routingKey*/);
                    channel.queueDeclare(rabbitInvalidMessageChannelQueue/*queue*/, rabbitQueueDurable/*durable*/, false/*exclusive*/, rabbitQueueAutoDelete/*autoDelete*/, rabbitQueueArgs/*extra args*/);
                    channel.queueBind(rabbitInvalidMessageChannelQueue/*queue*/, rabbitExchange/*exchange*/, rabbitInvalidMessageChannelQueue/*routingKey*/);
                    channel.basicConsume(rabbitQueue/*queue*/, false/*noAck*/, consumer);
                } catch (Exception e) {
                    if (!closed) {
                        logger.warn("failed to create queue [{}]", e, rabbitQueue);
                    }
                    cleanup(0, "failed to create queue");
                    continue;
                }

                // now use the queue to listen for messages
                while (true) {
                    if (closed) {
                        break;
                    }
                    QueueingConsumer.Delivery task;
                    try {
                        task = consumer.nextDelivery();
                    } catch (Exception e) {
                        if (!closed) {
                            logger.error("failed to get next message, reconnecting...", e);
                        }
                        cleanup(0, "failed to get message");
                        break;
                    }

                    if (task != null && task.getBody() != null) {
                        final List<Long> deliveryTags = Lists.newArrayList();
                        final List<byte[]> bodies =  Lists.newArrayList();
                        BulkRequestBuilder bulkRequestBuilder = client.prepareBulk();

                        try {
                            bodies.add(task.getBody());
                            bulkRequestBuilder.add(task.getBody(), 0, task.getBody().length, false);
                        } catch (Exception e) {
                            logger.warn("failed to parse request for delivery tag [{}], ack'ing...", e, task.getEnvelope().getDeliveryTag());
                            queueInvalidMessages(e.getMessage(), bodies);
                            try {
                                channel.basicAck(task.getEnvelope().getDeliveryTag(), false);
                            } catch (IOException e1) {
                                logger.warn("failed to ack [{}]", e1, task.getEnvelope().getDeliveryTag());
                            }
                            continue;
                        }

                        deliveryTags.add(task.getEnvelope().getDeliveryTag());

                        if (bulkRequestBuilder.numberOfActions() < bulkSize) {
                            // try and spin some more of those without timeout, so we have a bigger bulk (bounded by the bulk size)
                            try {
                                while ((task = consumer.nextDelivery(bulkTimeout.millis())) != null) {
                                    try {
                                        bodies.add(task.getBody());
                                        bulkRequestBuilder.add(task.getBody(), 0, task.getBody().length, false);
                                        deliveryTags.add(task.getEnvelope().getDeliveryTag());
                                    } catch (Exception e) {
                                        logger.warn("failed to parse request for delivery tag [{}], ack'ing...", e, task.getEnvelope().getDeliveryTag());
                                        queueInvalidMessages(e.getMessage(), bodies);
                                        try {
                                            channel.basicAck(task.getEnvelope().getDeliveryTag(), false);
                                        } catch (Exception e1) {
                                            logger.warn("failed to ack on failure [{}]", e1, task.getEnvelope().getDeliveryTag());
                                        }
                                    }
                                    if (bulkRequestBuilder.numberOfActions() >= bulkSize) {
                                        break;
                                    }
                                }
                            } catch (InterruptedException e) {
                                if (closed) {
                                    break;
                                }
                            }
                        }

                        if (logger.isTraceEnabled()) {
                            logger.trace("executing bulk with [{}] actions", bulkRequestBuilder.numberOfActions());
                        }

                        if (ordered) {
                            try {
                                BulkResponse response = null;

                                for (int try_num = 1; try_num <= maxTries; try_num++) {
                                    // Exponential backoff formula:
                                    //
                                    //   (try number / total tries)^2 * (max retry time - min retry time) + min retry time
                                    //
                                    // In other words, take the graph of y=x^2 from x=0 to x=1 and
                                    // scale it to the number of tries and the minimum and maximum wait time.

                                    response = bulkRequestBuilder.execute().actionGet();
                                    if (responseContainsRetriableFailures(response)) {
                                        float sleepTime = ((float)try_num/(float)maxTries);
                                        sleepTime = sleepTime * sleepTime * (maxRetryTime - minRetryTime) + minRetryTime;

                                        logger.warn("(try {} of {}) failed to execute, waiting {} seconds" + response.buildFailureMessage(), try_num, maxTries, sleepTime);

                                        Thread.sleep((long)(sleepTime * 1000));
                                    } else {
                                        break;
                                    }

                                    logger.warn("total failure after {} tries", maxTries);
                                }

                                if (response.hasFailures()) {
                                    queueInvalidMessages(response.buildFailureMessage(), bodies);
                                    logger.warn("failed to execute" + response.buildFailureMessage());
                                }
                                for (Long deliveryTag : deliveryTags) {
                                    try {
                                        channel.basicAck(deliveryTag, false);
                                    } catch (Exception e1) {
                                        logger.warn("failed to ack [{}]", e1, deliveryTag);
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn("failed to execute bulk", e);
                            }
                        } else {
                            bulkRequestBuilder.execute(new ActionListener<BulkResponse>() {
                                @Override
                                public void onResponse(BulkResponse response) {
                                    if (response.hasFailures()) {
                                        queueInvalidMessages(response.buildFailureMessage(), bodies);
                                        logger.warn("failed to execute" + response.buildFailureMessage());
                                    }
                                    ackAll();
                                }

                                /** Ack all messages */
                                private void ackAll() {
                                    for (Long deliveryTag : deliveryTags) {
                                        try {
                                            channel.basicAck(deliveryTag, false);
                                        } catch (Exception e1) {
                                            logger.warn("failed to ack [{}]", e1, deliveryTag);
                                        }
                                    }
                                }

                                @Override
                                public void onFailure(Throwable e) {
                                    if (rabbitInvalidMessageChannelQueue == null) {
                                        logger.warn("failed to execute bulk for delivery tags [{}], not ack'ing", e, deliveryTags);
                                    } else {
                                        logger.warn("failed to execute bulk for delivery tags [{}], ack'ing and delivering it to {}",
                                                e, deliveryTags, rabbitInvalidMessageChannelQueue);
                                        ackAll();
                                        queueInvalidMessages(e.getMessage(), bodies);
                                    }
                                }
                            });
                        }
                    }
                }
            }
            cleanup(0, "closing river");
        }

        private boolean responseContainsRetriableFailures(BulkResponse response) {
            if (response.hasFailures()) {
                for (BulkItemResponse item : response) {
                    if (item.isFailed()) {
                        if (!item.getFailureMessage().startsWith("MapperParsingException")) {
                            // Retry for anything that isn't a MapperParsingException.
                            // I wish I had a list of error messages so that I could
                            // make this list more comprehensive.  We don't want to
                            // retry MapperParsingExceptions because the message won't
                            // magically become parsable.
                            return true;
                        }
                    }
                }
                return false;
            } else {
                return false;
            }
        }

        private void queueInvalidMessages(final String message, final List<byte[]> bodies) {
            logger.info("Queuing {} invalid messages....", bodies.size());
            for (byte[] body : bodies) {
                try {
                    channel.basicPublish(rabbitExchange,
			    rabbitInvalidMessageChannelQueue,
                            MessageProperties.PERSISTENT_TEXT_PLAIN,
                            body);
                } catch (IOException e1) {
                    logger.error("Could not publish in the invalid message channel {}",
                            "#message: " + new String(body) + " #error:" + e1.getMessage(),
                            e1);
                }
            }
        }
        private void cleanup(int code, String message) {
            try {
                channel.close(code, message);
            } catch (Exception e) {
                logger.debug("failed to close channel on [{}]", e, message);
            }
            try {
                connection.close(code, message);
            } catch (Exception e) {
                logger.debug("failed to close connection on [{}]", e, message);
            }
        }
    }
}
