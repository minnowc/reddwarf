/*
 * Copyright 2007 Sun Microsystems, Inc. All rights reserved
 */

package com.sun.sgs.benchmark.client.listener;

public interface ServerMessageListener {
    void receivedMessage(byte[] message);
}
