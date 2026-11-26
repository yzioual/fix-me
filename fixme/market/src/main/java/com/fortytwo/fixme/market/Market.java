package com.fortytwo.fixme.market;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedList;

// TODO Step 2: extend the new abstract common.Client and connect on Utils.MARKET_PORT
public class Market {
    private final String name;
    public LinkedList<Instrument> instrumentsList;

    public Market(String name, LinkedList<Instrument> instrumentsList) {
        this.name = name;
        this.instrumentsList = instrumentsList;
    }

    /// Takes in a config file that contains Instruments and returns a list.
    public static LinkedList<Instrument> getInstruments(String path) {
        LinkedList<Instrument> instruments = new LinkedList<>();
        String name;
        String company;
        String sector;
        int price, quantity;
        boolean isTraded;

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                String[] parts = line.split(",");
                if (parts.length != 6) {
                    System.err.println("Invalid config line: " + line);
                    continue;
                }
                name = parts[0].trim();
                company = parts[1].trim();
                sector = parts[2].trim();
                price = Integer.parseInt(parts[3].trim());
                quantity = Integer.parseInt(parts[4].trim());
                isTraded = Boolean.parseBoolean(parts[5].trim());
                instruments.add(new Instrument(name, company, sector, price, quantity, isTraded));
            }
        } catch (IOException e) {
            System.out.println("Exception occurred " + e.getMessage());
        }

        return instruments;
    }

    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "config.txt";
        Market market = new Market("NASDAQ", getInstruments(path));
        System.out.println("[" + market.name + "]: loaded " + market.instrumentsList.size() + " instruments");
        // TODO Step 2: market.start();
    }
}
