package com.openuc2.control;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Request/reply on top of {@link UsbSerialManager}: received text is split
 * into lines and queued, so a stage driver can send a command and block for
 * the answer on its own worker thread. The console keeps receiving its copy
 * of the same bytes as before.
 */
public class SerialLink implements UsbSerialManager.RawListener {

    /** Unread lines kept while nobody is waiting; a chatty board must not grow this forever. */
    private static final int MAX_LINES = 2000;

    private final UsbSerialManager serial;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>(MAX_LINES);
    private final StringBuilder partial = new StringBuilder();   // read thread only

    public SerialLink(UsbSerialManager serial) {
        this.serial = serial;
    }

    @Override
    public void onRaw(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') {
                partial.append(c);
            } else if (partial.length() > 0) {
                offer(partial.toString().trim());
                partial.setLength(0);
            }
        }
    }

    void offer(String line) {
        if (line.isEmpty()) return;
        while (!lines.offer(line)) lines.poll();   // full: drop the oldest
    }

    /** Forget stale lines; call right before sending a command whose reply you want. */
    public void clear() {
        lines.clear();
    }

    public void send(String command) throws IOException {
        if (!serial.isConnected()) throw new IOException("Stage not connected");
        serial.send(command, false);
    }

    public String readLine(long timeoutMs) throws InterruptedException, TimeoutException {
        String line = lines.poll(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
        if (line == null) throw new TimeoutException("no reply from the stage");
        return line;
    }
}
