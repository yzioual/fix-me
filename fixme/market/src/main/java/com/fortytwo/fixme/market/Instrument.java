package com.fortytwo.fixme.market;

public class Instrument {
    public final String name;
    private final String company;
    private final String sector;
    private final int price; // in USD
    private int quantity; // shares
    private final boolean isTraded;

    public Instrument(String name,
                      String company,
                      String sector,
                      int price,
                      int quantity,
                      boolean isTraded) {
        this.name = name;
        this.company = company;
        this.sector = sector;
        this.price = price;
        this.quantity = quantity;
        this.isTraded = isTraded;
    }

    public synchronized boolean isTraded() {
        return isTraded;
    }

    public synchronized boolean buy(int quantity) {
        if (!isTraded || quantity > this.quantity) {
            return false;
        }
        this.quantity -= quantity;
        return true;
    }

    public synchronized boolean sell(int quantity) {
        if (!isTraded) {
            return false;
        }
        this.quantity += quantity;
        return true;
    }

    public synchronized int getQuantity() {
        return quantity;
    }

    @Override
    public String toString() {
        return name + " " +  company + " " + sector + " " + price + " " + quantity + " " + isTraded;
    }
}