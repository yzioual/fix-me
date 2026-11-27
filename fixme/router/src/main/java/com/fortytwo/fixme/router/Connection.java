package com.fortytwo.fixme.router;

import com.fortytwo.fixme.common.MessageReader;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Connection {
    int id;
    boolean isBroker;
    final MessageReader reader;
    final ConcurrentLinkedQueue<ByteBuffer> outbound;
    SelectionKey key;

    public Connection(int id) {
        this.id = id;
        this.reader = new MessageReader();
        this.outbound = new ConcurrentLinkedQueue<>();
    }
}