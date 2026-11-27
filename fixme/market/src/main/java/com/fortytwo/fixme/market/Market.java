package com.fortytwo.fixme.market;

import com.fortytwo.fixme.common.Client;
import com.fortytwo.fixme.common.Utils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Market extends Client {
    private static final String DEFAULT_CONFIG = "config.txt";
    private static final int NUMBER_THREADS = 4;

    private final Map<String, Instrument> instruments;
    private final ExecutorService pool = Executors.newFixedThreadPool(NUMBER_THREADS);

    public Market(String configPath) {
        super("Market", Utils.MARKET_PORT);
        this.instruments = loadInstruments(configPath);
    }

    @Override
    protected void onIdAssigned(int id) {
        System.out.println("[Market] ready with id " + id + " (" + instruments.size() + " instruments listed)");
    }

    @Override
    protected void onMessage(Map<String, String> fields) {
        if ("D".equals(fields.get("35"))) {
            pool.submit(() -> execute(fields));
        }
    }

    private void execute(Map<String, String> order) {
        String brokerId = order.get("49");
        int qty;
        int price;
        String orderId = order.get("11");
        String symbol = order.get("55");
        String side = order.get("54");
        String qtyStr = order.get("38");
        String priceStr = order.get("44");

        if (brokerId == null) {
            System.out.println("[Market] dropped order without sender (49): nobody to reply to");
            return;
        }
        if (symbol == null || side == null || qtyStr == null || priceStr == null) {
            reply(brokerId, orderId, symbol, false, "missing field (55, 54, 38 or 44)");
            return;
        }

        try {
            qty = Integer.parseInt(qtyStr);
            price = Integer.parseInt(priceStr);
        } catch (NumberFormatException e) {
            reply(brokerId, orderId, symbol, false, "quantity and price must be integers");
            return;
        }
        if (qty <= 0 || price <= 0) {
            reply(brokerId, orderId, symbol, false, "quantity and price must be positive");
            return;
        }

        Instrument instrument = instruments.get(symbol);
        if (instrument == null) {
            reply(brokerId, orderId, symbol, false, "instrument not listed on this market");
            return;
        }

        boolean executed;
        switch (side) {
            case "1" -> executed = instrument.buy(qty);
            case "2" -> executed = instrument.sell(qty);
            default -> {
                reply(brokerId, orderId, symbol, false, "invalid side (54 must be 1 or 2)");
                return;
            }
        }

        if (!executed) {
            reply(brokerId, orderId, symbol, false,
                    side.equals("1") ? "not traded or not enough quantity" : "instrument not traded");
            return;
        }
        reply(brokerId, orderId, symbol, true, null);
        System.out.println("[Market] " + symbol + " stock is now " + instrument.getQuantity());
    }

    private void reply(String brokerId, String orderId, String symbol, boolean executed, String reason) {
        Map<String, String> report = new LinkedHashMap<>();
        report.put("49", String.valueOf(id));
        report.put("35", "8");
        report.put("56", brokerId);
        if (orderId != null) report.put("11", orderId);
        if (symbol != null) report.put("55", symbol);
        report.put("39", executed ? "2" : "8");
        if (!executed) report.put("58", reason);
        send(report);
        System.out.println("[Market] " + (executed ? "EXECUTED" : "REJECTED") + " order " + orderId
                + " from " + brokerId + (executed ? "" : " (" + reason + ")"));
    }

    private static Map<String, Instrument> loadInstruments(String path) {
        Map<String, Instrument> book = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                String[] parts = line.split(",");
                if (parts.length != 6) {
                    System.err.println("[Market] skipping invalid config line: " + line);
                    continue;
                }
                try {
                    Instrument instrument = new Instrument(
                            parts[0].trim(),
                            parts[1].trim(),
                            parts[2].trim(),
                            Integer.parseInt(parts[3].trim()),
                            Integer.parseInt(parts[4].trim()),
                            Boolean.parseBoolean(parts[5].trim()));
                    book.put(instrument.name, instrument);
                } catch (NumberFormatException e) {
                    System.err.println("[Market] skipping config line with bad number: " + line);
                }
            }
        } catch (IOException e) {
            System.err.println("[Market] cannot read config '" + path + "': " + e.getMessage());
        }
        return book;
    }

    static void main(String[] args) throws InterruptedException {
        String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG;
        Market market = new Market(configPath);
        if (market.instruments.isEmpty()) {
            System.err.println("[Market] no instruments loaded from '" + configPath + "', exiting");
            System.exit(1);
        }
        market.start();
        market.awaitId();
    }
}
