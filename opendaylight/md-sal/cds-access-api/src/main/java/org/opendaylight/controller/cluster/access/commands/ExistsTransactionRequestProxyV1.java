/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.access.commands;

/**
 * Externalizable proxy for use with {@link ExistsTransactionRequest}. It implements the initial (Boron) serialization
 * format.
 */
@Deprecated(since = "7.0.0", forRemoval = true)
final class ExistsTransactionRequestProxyV1 extends AbstractReadPathTransactionRequestProxyV1<ExistsTransactionRequest>
        implements ExistsTransactionRequest.SerialForm {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    // checkstyle flags the public modifier as redundant however it is explicitly needed for Java serialization to
    // be able to create instances via reflection.
    @SuppressWarnings("checkstyle:RedundantModifier")
    public ExistsTransactionRequestProxyV1() {
        // For Externalizable
    }

    ExistsTransactionRequestProxyV1(final ExistsTransactionRequest request) {
        super(request);
    }
}
