package com.fortytwo.fixme.router;

import com.fortytwo.fixme.common.FIXMessage;
import com.fortytwo.fixme.common.Utils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Router {
    private static final int ID_FIRST_ELEMENT = 100000;
    private static final int NUMBER_THREADS = 4;
    private static Router instance = null;
    private Selector selector;
    private ServerSocketChannel brokerSocketChannel;
    private ServerSocketChannel marketSocketChannel;
    private final AtomicInteger nextId = new AtomicInteger(ID_FIRST_ELEMENT);
    private final ConcurrentHashMap<Integer, Connection> routingTable = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(NUMBER_THREADS);
    private final Handler chain;
    private final ConcurrentLinkedQueue<Connection> needsWrite = new ConcurrentLinkedQueue<>();

    private Router() {
        chain = new ChecksumHandler();
        chain
                .setNext(new DestinationHandler(routingTable, this::requestWrite))
                .setNext(new ForwardHandler(this::requestWrite));
    }

    public static Router getInstance() {
        if (instance == null) {
            instance = new Router();
        }
        return instance;
    }

    private void requestWrite(Connection c) {
        needsWrite.add(c);
        selector.wakeup();
    }

    public void activate() throws IOException {
        this.selector = Selector.open();
        this.brokerSocketChannel = openServer(Utils.BROKER_PORT);
        this.marketSocketChannel = openServer(Utils.MARKET_PORT);
        System.out.println("[Router] listening on port " + Utils.BROKER_PORT + " (brokers)");
        System.out.println("[Router] listening on port " + Utils.MARKET_PORT + " (markets)");

        while (true) {
            // Workers asked to write (see requestWrite): only this thread touches selection keys.
            Connection c;
            while ((c = needsWrite.poll()) != null) {
                SelectionKey k = c.key;
                if (k.isValid()) k.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }

            if (selector.select() == 0)
                continue;

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove();
                try {
                    if (key.isAcceptable())
                        accept(key);

                    if (key.isValid() && key.isWritable())
                        write(key);

                    if (key.isValid() && key.isReadable())
                        read(key);
                } catch (IOException e) {
                    closeConnection(key); // one broken client never stops the router
                }
            }
        }
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverSocketChannel.accept();
        if (socketChannel == null) {
            return;
        }
        socketChannel.configureBlocking(false);

        boolean isBroker = serverSocketChannel == this.brokerSocketChannel;
        Connection connection = new Connection(nextId.getAndIncrement(), isBroker);
        connection.key = socketChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, connection);
        this.routingTable.put(connection.id, connection);

        Map<String, String> logon = new LinkedHashMap<>();
        logon.put("49", DestinationHandler.ROUTER_ID);
        logon.put("35", "A");
        logon.put("56", String.valueOf(connection.id));
        connection.outbound.add(FIXMessage.addHeader(FIXMessage.build(logon).getBytes(StandardCharsets.US_ASCII)));

        System.out.println("[Router] " + connection.label() + " connected");
    }

    private void write(SelectionKey key) throws IOException {
        SocketChannel socket = (SocketChannel) key.channel();
        Connection conn = (Connection) key.attachment();
        ByteBuffer buff;
        while ((buff = conn.outbound.peek()) != null) {
            socket.write(buff);
            if (buff.hasRemaining()) break;
            conn.outbound.poll();
        }
        if (conn.outbound.isEmpty()) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel socket = (SocketChannel) key.channel();
        Connection conn = (Connection) key.attachment();
        byte[] message;

        while ((message = conn.reader.read(socket)) != null) {
            String raw = new String(message, StandardCharsets.US_ASCII);
            pool.submit(() -> chain.handle(new RoutedMessage(conn, raw)));
        }
    }

    private void closeConnection(SelectionKey key) {
        if (key.attachment() instanceof Connection connection) {
            key.cancel();
            try { key.channel().close(); } catch (IOException ignore) {}
            this.routingTable.remove(connection.id);
            System.out.println("[Router] " + connection.label() + " disconnected");
        }
    }

    private ServerSocketChannel openServer(int port) throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(new InetSocketAddress(port));
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        return serverSocketChannel;
    }

    static void main(String[] args) {
        try {
            Router.getInstance().activate();
        } catch (IOException e) {
            System.out.println("[Router] fatal: " + e.getMessage());
        }
    }
}