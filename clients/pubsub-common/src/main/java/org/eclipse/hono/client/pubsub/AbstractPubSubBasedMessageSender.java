/**
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hono.client.pubsub;

import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.client.util.ServiceClient;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.Lifecycle;
import org.eclipse.hono.util.LifecycleStatus;
import org.eclipse.hono.util.MessagingClient;
import org.eclipse.hono.util.MessagingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.Json;
import io.vertx.ext.healthchecks.HealthCheckHandler;
import io.vertx.ext.healthchecks.Status;

/**
 * A sender for publishing messages to Pub/Sub.
 */
public abstract class AbstractPubSubBasedMessageSender implements MessagingClient, ServiceClient, Lifecycle {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    private final PubSubPublisherFactory publisherFactory;
    private final String topic;
    private final Tracer tracer;
    private final String projectId;
    private LifecycleStatus lifecycleStatus = new LifecycleStatus();

    /**
     * Creates a new PubSub-based message sender.
     *
     * @param publisherFactory The factory to use for creating Pub/Sub publishers.
     * @param topic The topic to create the publisher for.
     * @param projectId The Google project id to use.
     * @param tracer The OpenTracing tracer.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public AbstractPubSubBasedMessageSender(
            final PubSubPublisherFactory publisherFactory,
            final String topic,
            final String projectId,
            final Tracer tracer) {
        Objects.requireNonNull(publisherFactory);
        Objects.requireNonNull(topic);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(tracer);

        this.publisherFactory = publisherFactory;
        this.topic = topic;
        this.projectId = projectId;
        this.tracer = tracer;
    }

    /**
     * Creates a new PubSub-based message sender.
     * <p>
     * To be used for unit tests.
     *
     * @param publisherFactory The factory to use for creating Pub/Sub publishers.
     * @param topic The topic to create the publisher for.
     * @param projectId The Google project id to use.
     * @param tracer The OpenTracing tracer.
     * @param lifecycleStatus The lifecycle to use for this class.
     */
    protected AbstractPubSubBasedMessageSender(
            final PubSubPublisherFactory publisherFactory,
            final String topic,
            final String projectId,
            final Tracer tracer,
            final LifecycleStatus lifecycleStatus) {

        this.publisherFactory = publisherFactory;
        this.topic = topic;
        this.projectId = projectId;
        this.tracer = tracer;
        this.lifecycleStatus = lifecycleStatus;
    }

    private static Span newSpan(final Tracer tracer, final String operationName, final String topic,
            final String referenceType, final SpanContext parent) {

        return TracingHelper.buildSpan(tracer, parent, operationName, referenceType)
                .ignoreActiveSpan()
                .withTag(Tags.COMPONENT.getKey(), "hono-client-pubsub")
                .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_PRODUCER)
                .withTag(Tags.MESSAGE_BUS_DESTINATION.getKey(), topic)
                .withTag(Tags.PEER_SERVICE.getKey(), "pubsub")
                .start();
    }

    @Override
    public final MessagingType getMessagingType() {
        return MessagingType.pubsub;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Registers a procedure for checking if this client's initial Pub/Sub client creation succeeded.
     * </p>
     */
    @Override
    public void registerReadinessChecks(final HealthCheckHandler readinessHandler) {
        readinessHandler.register(
                "%s-pub-sub-publisher-creation-%s".formatted(topic, UUID.randomUUID()),
                status -> status.tryComplete(new Status().setOk(lifecycleStatus.isStarted())));
    }

    @Override
    public void registerLivenessChecks(final HealthCheckHandler livenessHandler) {
        // no liveness checks to be added
    }

    @Override
    public Future<Void> start() {
        if (lifecycleStatus.isStarting()) {
            return Future.succeededFuture();
        } else if (!lifecycleStatus.setStarting()) {
            return Future.failedFuture(new IllegalStateException("sender is already started/stopping"));
        }
        lifecycleStatus.setStarted();
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> stop() {
        if (lifecycleStatus.isStopping()) {
            return Future.succeededFuture();
        } else if (!lifecycleStatus.setStopping()) {
            return Future.failedFuture(new IllegalStateException("sender is already stopping"));
        }
        lifecycleStatus.setStopped();
        return publisherFactory.closeAllPublisher();
    }

    /**
     * Sends a message to a Pub/Sub client and waits for outcome.
     *
     * @param topic The topic to send the message to.
     * @param tenantId The tenantId that the device belongs to.
     * @param deviceId The device identifier.
     * @param payload The data to send or {@code null} if the message has no payload.
     * @param properties Additional metadata that should be included in the message.
     * @param currentSpan The <em>OpenTracing</em> span used to use for tracking the sending of the message. The span
     *            will <em>not</em> be finished by this method.
     * @return A future indicating the outcome of the operation.
     *         <p>
     *         The future will be succeeded if the message has been sent.
     *         <p>
     *         The future will be failed with a {@link org.eclipse.hono.client.ServerErrorException} if the data could
     *         not be sent. The error code contained in the exception indicates the cause of the failure.
     * @throws NullPointerException if topic, tenantId, deviceId or span are {@code null}.
     */
    protected final Future<Void> sendAndWaitForOutcome(
            final String topic,
            final String tenantId,
            final String deviceId,
            final Buffer payload,
            final Map<String, Object> properties,
            final Span currentSpan) {

        Objects.requireNonNull(topic);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(properties);
        Objects.requireNonNull(currentSpan);

        if (!lifecycleStatus.isStarted()) {
            return Future.failedFuture(
                    new ServerErrorException(HttpURLConnection.HTTP_UNAVAILABLE, "sender not started"));
        }

        final Map<String, String> pubSubAttributes = encodePropertiesAsPubSubAttributes(properties, currentSpan);
        final ByteString data = ByteString.copyFrom(payload.getBytes());
        final PubsubMessage pubsubMessage = PubsubMessage.newBuilder().putAllAttributes(pubSubAttributes)
                .setOrderingKey(deviceId).setData(data).build();

        log.trace("sending message to Pub/Sub [topic: {}, registry: {}, deviceId: {}]", topic, tenantId, deviceId);
        logPubSubMessage(currentSpan, pubsubMessage, topic, tenantId);

        return getOrCreatePublisher(topic, tenantId).publish(pubsubMessage)
                .onSuccess(recordMessage -> {
                    logPubSubMessageId(currentSpan, topic, recordMessage);
                })
                .onFailure(t -> {
                    logError(currentSpan, topic, tenantId, deviceId, t);
                    throw new ServerErrorException(tenantId, getErrorCode(), t);
                })
                .mapEmpty();

    }

    /**
     * Creates a new <em>OpenTracing</em> span to trace publishing messages to Pub/Sub.
     *
     * @param operationName The operation name to set for the span.
     * @param topic The topic to which the message is sent.
     * @param tenantId The tenant identifier related to the operation.
     * @param deviceId The device identifier related to the operation.
     * @param referenceType The type of reference towards the given span context.
     * @param context The span context to set as parent and to derive the sampling priority from (may be null).
     * @return The new span.
     * @throws NullPointerException if tracer or topic is {@code null}.
     */
    protected Span startSpan(final String operationName, final String topic, final String tenantId,
            final String deviceId, final String referenceType, final SpanContext context) {
        Objects.requireNonNull(operationName);
        Objects.requireNonNull(topic);
        Objects.requireNonNull(referenceType);
        return newSpan(tracer, operationName, topic, referenceType, context)
                .setTag(TracingHelper.TAG_TENANT_ID.getKey(), tenantId)
                .setTag(TracingHelper.TAG_DEVICE_ID.getKey(), deviceId);
    }

    private PubSubPublisherClient getOrCreatePublisher(final String topic, final String tenantId) {
        return publisherFactory.getOrCreatePublisher(topic, projectId, tenantId);
    }

    private void logPubSubMessageId(final Span span, final String topic, final String messageId) {
        log.trace("message published to PubSub [topic: {}, id: {}]", topic, messageId);
        span.log("message published to PubSub");

        Tags.HTTP_STATUS.set(span, HttpURLConnection.HTTP_ACCEPTED);
    }

    private void logPubSubMessage(final Span span, final PubsubMessage message, final String topic,
            final String tenantId) {
        final String attributesAsString = message.getAttributesMap()
                .keySet()
                .stream()
                .map(key -> key + "=" + message.getAttributesMap().get(key))
                .collect(Collectors.joining(",", "{", "}"));
        log.trace("producing message [topic: {}, tenant: {}, key: {}, timestamp: {}, attributes: {}]",
                topic, tenantId, message.getOrderingKey(), message.getPublishTime(), attributesAsString);

        span.log("publishing message with headers: " + attributesAsString);
    }

    private void logError(
            final Span span,
            final String topic,
            final String tenantId,
            final String deviceId,
            final Throwable cause) {
        log.debug("sending message failed [topic: {}, key: {}, tenantId: {}, deviceId: {}]", topic, deviceId, tenantId,
                deviceId, cause);

        Tags.HTTP_STATUS.set(span, getErrorCode());
        TracingHelper.logError(span, cause);
    }

    private int getErrorCode() {
        return HttpURLConnection.HTTP_UNAVAILABLE;
    }

    private Map<String, String> encodePropertiesAsPubSubAttributes(final Map<String, Object> properties,
            final Span span) {
        final Map<String, String> attributes = new HashMap<>();
        properties.forEach((key, value) -> {
            try {
                attributes.put(key, getStringEncodedValue(value));
            } catch (final EncodeException e) {
                log.info("failed to serialize property with key [{}] to Pub/Sub attribute", key);
                span.log("failed to create Pub/Sub attributes from property: " + key);
            }
        });

        return attributes;
    }

    private String getStringEncodedValue(final Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        return Json.encode(value);
    }
}