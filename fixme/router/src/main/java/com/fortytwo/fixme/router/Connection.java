package com.fortytwo.fixme.router;

import com.fortytwo.fixme.common.MessageReader;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Connection {
    final int id;
    final boolean isBroker;
    final MessageReader reader = new MessageReader();
    final ConcurrentLinkedQueue<ByteBuffer> outbound = new ConcurrentLinkedQueue<>();
    SelectionKey key; // set once in accept(); used only by the selector thread

    public Connection(int id, boolean isBroker) {
        this.id = id;
        this.isBroker = isBroker;
    }

    String label() {
        return (isBroker ? "Broker " : "Market ") + id;
    }
}
