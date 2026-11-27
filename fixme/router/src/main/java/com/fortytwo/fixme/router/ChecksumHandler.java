package com.fortytwo.fixme.router;

import com.fortytwo.fixme.common.FIXMessage;

public class ChecksumHandler extends Handler {
    @Override
    public void handle(RoutedMessage message) {
        boolean isMessageValid = FIXMessage.isChecksumValid(message.raw);
        if (!isMessageValid) {
            System.out.println("Invalid checksum from " + message.from.id);
            return;
        }
        super.handle(message);
    }
}
