package com.fortytwo.fixme.router;

public abstract class Handler {
    private Handler next;

    public Handler setNext(Handler n) {
        this.next = n;
        return n;
    }

    public void handle(RoutedMessage routedMessage) {
        if (this.next != null) {
            this.next.handle(routedMessage);
        }
    }
}
