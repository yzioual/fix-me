package com.fortytwo.fixme.router;

import java.util.concurrent.ConcurrentHashMap;

public class DestinationHandler extends Handler {
    private final ConcurrentHashMap<Integer, Connection> routingTable;

    public DestinationHandler(ConcurrentHashMap<Integer, Connection> routingTable) {
        this.routingTable = routingTable;
    }

    private boolean checkFails(RoutedMessage message) {
        String destinationIdStr = message.fields.get("56");
        int destinationId;

        try {
            destinationId = Integer.parseInt(destinationIdStr);
            Connection connection = routingTable.get(destinationId);
            if (connection == null) {
                System.out.println("Router rejected request, no destination with id " + destinationId +
                        " exists in Routing Table");
                return true;
            }

            if (connection.isBroker == message.from.isBroker) {
                System.out.println("[Router] rejected request, ");
                System.out.println(message.from.isBroker ? "broker → broker" : "market → market");
                return true;
            }

            message.to = connection;
            return false;
        } catch (NumberFormatException e) {
            System.out.println("Router rejected request, no destination id is not a number");
            return true;
        }
    }

    @Override
    public void handle(RoutedMessage routedMessage) {
        if (checkFails(routedMessage)) return;
        super.handle(routedMessage);
    }
}