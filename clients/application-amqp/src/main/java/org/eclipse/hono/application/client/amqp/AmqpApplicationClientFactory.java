/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.application.client.amqp;

import org.eclipse.hono.application.client.ApplicationClientFactory;
import org.eclipse.hono.util.Lifecycle;

/**
 * A factory for creating clients for Hono's AMQP-based northbound APIs.
 */
public interface AmqpApplicationClientFactory extends ApplicationClientFactory<AmqpMessageContext>, Lifecycle {
}
