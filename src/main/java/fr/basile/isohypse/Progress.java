package fr.basile.isohypse;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * The progress of one long step, logged every {@link #EVERY} percent. Safe to advance from the threads of a parallel stream.
 */
final class Progress {

    private static final int EVERY = 10;

    private final Logger logger;
    private final String label;
    private final long total;
    private final AtomicLong done = new AtomicLong();
    private final AtomicInteger logged = new AtomicInteger();

    Progress(Logger logger, String label, long total) {
        this.logger = logger;
        this.label = label;
        this.total = Math.max(1, total);
    }

    /** Counts {@code units} more as done, and logs each multiple of {@link #EVERY} percent reached. */
    void advance(long units) {
        int percent = (int) Math.min(100, done.addAndGet(units) * 100 / total) / EVERY * EVERY;
        int before = logged.get();
        while (percent > before) {
            if (logged.compareAndSet(before, percent)) {
                logger.info(label + " : " + percent + " %");
                return;
            }
            before = logged.get();
        }
    }
}
