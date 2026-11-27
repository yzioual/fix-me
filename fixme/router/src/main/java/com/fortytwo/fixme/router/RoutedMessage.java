package com.fortytwo.fixme.router;

import com.fortytwo.fixme.common.FIXMessage;

import java.util.Map;

public class RoutedMessage {
    final Connection from;
    final String raw;
    final Map<String, String> fields;
    Connection to;

    public RoutedMessage(Connection from, String raw) {
        this.from = from;
        this.raw = raw;
        this.fields = FIXMessage.parse(raw);
    }
}
