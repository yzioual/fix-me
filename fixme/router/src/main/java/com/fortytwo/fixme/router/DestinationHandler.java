package com.fortytwo.fixme.router;

import com.fortytwo.fixme.common.FIXMessage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class DestinationHandler extends Handler {
    private static final String ROUTER_ID = "000000";
    private final ConcurrentHashMap<Integer, Connection> routingTable;
    private final Consumer<Connection> requestWrite;

    public DestinationHandler(
            ConcurrentHashMap<Integer, Connection> routingTable,
            Consumer<Connection> requestWrite
    ) {
        this.routingTable = routingTable;
        this.requestWrite = requestWrite;
    }

    private boolean checkFails(RoutedMessage message) {
        String destinationIdStr = message.fields.get("56");
        int destinationId;
        try {
            destinationId = Integer.parseInt(destinationIdStr);
        } catch (NumberFormatException e) {
            reject(message, "invalid destination id '" + destinationIdStr + "'");
            return true;
        }

        Connection destination = routingTable.get(destinationId);
        if (destination == null) {
            reject(message, "unknown destination " + destinationId);
            return true;
        }
        if (destination.isBroker == message.from.isBroker) {
            reject(message, message.from.isBroker ? "broker -> broker not allowed" : "market -> market not allowed");
            return true;
        }

        message.to = destination;
        return false;
    }

    private void reject(RoutedMessage message, String reason) {
        System.out.println("[Router] rejected message from " + message.from.label() + ": " + reason);
        Map<String, String> rejection = new LinkedHashMap<>();
        rejection.put("49", ROUTER_ID);
        rejection.put("35", "8");
        rejection.put("56", String.valueOf(message.from.id));
        String orderId = message.fields.get("11");
        if (orderId != null) rejection.put("11", orderId);
        String symbol = message.fields.get("55");
        if (symbol != null) rejection.put("55", symbol);
        rejection.put("39", "8");
        rejection.put("58", reason);
        String rejectionStr = FIXMessage.build(rejection);
        ByteBuffer buffer = FIXMessage.addHeader(rejectionStr.getBytes(StandardCharsets.US_ASCII));
        message.from.outbound.add(buffer);
        requestWrite.accept(message.from);
    }

    @Override
    public void handle(RoutedMessage message) {
        if (checkFails(message)) return;
        super.handle(message);
    }
}
