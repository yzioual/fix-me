package com.fortytwo.fixme.router;

import com.fortytwo.fixme.common.FIXMessage;

public class ChecksumHandler extends Handler {
    @Override
    public void handle(RoutedMessage message) {
        if (!FIXMessage.isChecksumValid(message.raw)) {
            System.out.println("[Router] rejected message from " + message.from.label() + ": invalid checksum");
            return;
        }
        super.handle(message);
    }
}
