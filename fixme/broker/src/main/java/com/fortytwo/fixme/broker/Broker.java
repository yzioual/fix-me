package com.fortytwo.fixme.broker;

import com.fortytwo.fixme.common.Client;
import com.fortytwo.fixme.common.Utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

public class Broker extends Client {
    private static final String USAGE =
            "usage: buy|sell <SYMBOL> <QUANTITY> <PRICE> <MARKET_ID>   e.g. buy AAPL 10 192 100001\n"
                    + "       quit";

    private final AtomicInteger orderSeq = new AtomicInteger(1);

    public Broker() {
        super("Broker", Utils.BROKER_PORT);
    }

    @Override
    protected void onIdAssigned(int id) {
        System.out.println("[Broker] ready with id " + id);
        System.out.println(USAGE);
    }

    @Override
    protected void onMessage(Map<String, String> fields) {
        if (!"8".equals(fields.get("35"))) {
            System.out.println("[Broker] ignoring unexpected message: " + fields);
            return;
        }
        String status = fields.get("39");
        String report = "order " + fields.get("11") + ": " + fields.get("55") + " (market " + fields.get("49") + ")";

        if ("2".equals(status)) {
            System.out.println("[Broker] EXECUTED " + report);
        } else if ("8".equals(status)) {
            System.out.println("[Broker] REJECTED " + report + " -> " + fields.get("58"));
        } else {
            System.out.println("[Broker] unknown status '" + status + "' for " + report);
        }
    }

    private boolean placeOrder(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 5) {
            return false;
        }

        String side;
        switch (parts[0].toLowerCase()) {
            case "buy" -> side = "1";
            case "sell" -> side = "2";
            default -> { return false; }
        }

        String symbol = parts[1].toUpperCase();
        if (symbol.contains("|") || symbol.contains("=")) {
            return false;
        }

        try {
            int qty = Integer.parseInt(parts[2]);
            int price = Integer.parseInt(parts[3]);
            int marketId = Integer.parseInt(parts[4]);
            if (qty <= 0 || price <= 0 || marketId <= 0) {
                return false;
            }

            String orderId = String.valueOf(orderSeq.getAndIncrement());
            Map<String, String> order = new LinkedHashMap<>();
            order.put("49", String.valueOf(id));
            order.put("35", "D");
            order.put("56", String.valueOf(marketId));
            order.put("11", orderId);
            order.put("55", symbol);
            order.put("54", side);
            order.put("38", String.valueOf(qty));
            order.put("44", String.valueOf(price));
            send(order);

            System.out.println("[Broker] sent order " + orderId + ": " + parts[0].toLowerCase()
                    + " " + qty + " " + symbol + " @ " + price + " to market " + marketId);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static void main() throws InterruptedException {
        Broker broker = new Broker();
        broker.start();
        broker.awaitId();

        Scanner in = new Scanner(System.in);
        while (in.hasNextLine()) {
            String line = in.nextLine().trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("quit")) break;
            if (!broker.placeOrder(line)) {
                System.out.println("[Broker] invalid command");
                System.out.println(USAGE);
            }
        }

        System.out.println("[Broker] bye");
        System.exit(0);
    }
}
