package com.fortytwo.fixme.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

public abstract class Client {
    protected final String name;
    private final int port;
    protected volatile int id = -1;
    private final CountDownLatch idLatch = new CountDownLatch(1);
    private final ConcurrentLinkedQueue<ByteBuffer> outbound = new ConcurrentLinkedQueue<>();
    private Selector selector;
    private SocketChannel socketChannel;
    private volatile boolean connected = false;
    private SelectionKey key;

    public Client(String name, int port) {
        this.name = name;
        this.port = port;
    }

    public void start() {
        new Thread(this::loop, this.name + "-io").start();
    }

    public void send(Map<String, String> fields) {
        String message = FIXMessage.build(fields);
        ByteBuffer buffer = FIXMessage.addHeader(message.getBytes(StandardCharsets.US_ASCII));
        this.outbound.add(buffer);
        this.selector.wakeup();
    }

    public void awaitId() throws InterruptedException {
        this.idLatch.await();
    }

    private void dispatch(byte[] raw) {
      Map<String, String> fields = FIXMessage.parse(new String(raw, StandardCharsets.US_ASCII));

      if ("A".equals(fields.get("35"))) {
          try {
              this.id = Integer.parseInt(fields.get("56")); // 56 is id - obviously :)
              this.idLatch.countDown();
              onIdAssigned(this.id);
              return;
          } catch (NumberFormatException e) {
              System.out.println("Client got an invalid logon (id)");
              return;
          }
      }
      onMessage(fields);
    }

    protected abstract void onIdAssigned(int id);
    protected abstract void onMessage(Map<String,String> fields);

    private void loop() {
        try {
            this.selector = Selector.open();
            this.socketChannel = SocketChannel.open();
            this.socketChannel.configureBlocking(false);
            this.socketChannel.connect(new InetSocketAddress(Utils.HOST, port));
            this.key = this.socketChannel.register(this.selector, SelectionKey.OP_CONNECT);


            while (true) {
                if (connected && !outbound.isEmpty()) {
                    key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                }

                if (this.selector.select() == 0) continue;

                Set<SelectionKey> keys = this.selector.selectedKeys();
                Iterator<SelectionKey> iterator = keys.iterator();

                while (iterator.hasNext()) {
                    SelectionKey localKey = iterator.next();
                    if (localKey.isConnectable()) {
                        SocketChannel client = (SocketChannel) localKey.channel();

                        if (client.finishConnect()) {
                            localKey.interestOps(SelectionKey.OP_READ);
                            localKey.attach(new MessageReader());
                            connected = true;
                        }
                    } else if (localKey.isReadable()) {
                        SocketChannel clientChannel = (SocketChannel) localKey.channel();
                        MessageReader messageReader = (MessageReader) localKey.attachment();

                        byte[] message;
                        try {
                            while ((message = messageReader.read(clientChannel)) != null) {
                                dispatch(message);
                            }
                        } catch (IOException e) {
                            System.out.println("[" + this.name + "] " +  "Router disconnected, exiting now..");
                            System.exit(1);
                        }
                    } else if (localKey.isWritable()) {
                        SocketChannel socket = (SocketChannel) localKey.channel();
                        ByteBuffer buf;
                        while ((buf = outbound.peek()) != null) {
                            socket.write(buf);
                            if (buf.hasRemaining()) break;
                            outbound.poll();
                        }

                        if (outbound.isEmpty()) {
                            localKey.interestOps(SelectionKey.OP_READ);
                        }
                    }
                    iterator.remove();
                }
            }
        } catch (IOException e) {
            System.out.println("[" + this.name + "] " + "Connection failed due to an I/O error." + " " + e.getMessage());
            System.exit(1);
        }
    }
}
