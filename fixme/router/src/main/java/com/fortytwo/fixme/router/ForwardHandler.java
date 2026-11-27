package com.fortytwo.fixme.router;

import com.fortytwo.fixme.common.FIXMessage;

import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.US_ASCII;

public class ForwardHandler extends Handler {
    private final Consumer<Connection> requestWrite;

    public ForwardHandler(Consumer<Connection> requestWrite) {
        this.requestWrite = requestWrite;
    }

    @Override
    public void handle(RoutedMessage routedMessage) {
        routedMessage.to.outbound.add(FIXMessage.addHeader(routedMessage.raw.getBytes(US_ASCII)));
        this.requestWrite.accept(routedMessage.to);
        System.out.println("[Router] forwarding " + routedMessage.from.label() + " -> " + routedMessage.to.label());
    }
}
