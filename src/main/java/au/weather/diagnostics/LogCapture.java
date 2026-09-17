package au.weather.diagnostics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The appender that turns every {@code WARN} and {@code ERROR} this application logs into a line the
 * diagnostics layer can serve (D-234). Attached to Logback's root logger at construction, beside the
 * console appender the yml configures, so nothing about how the log is written changes.
 *
 * <p>Two rules, both about never making the logger wait. The line is reduced to strings on the
 * logging thread — the formatted message, the throwable's class and a trimmed trace — and offered to a
 * bounded queue; a full queue drops the line and counts the drop. And nothing here logs: a warning
 * raised while draining the queue would be captured, drained, and raised again, so the store mutes
 * capture on its own thread while it writes.
 */
@Component
public class LogCapture extends AppenderBase<ILoggingEvent> {

    static final String NAME = "weather-diagnostics";
    private static final ThreadLocal<Boolean> MUTED = ThreadLocal.withInitial(() -> false);

    private final BlockingQueue<Captured> queue;
    private final AtomicLong captured = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public LogCapture(DiagnosticsProperties properties) {
        this.queue = new ArrayBlockingQueue<>(Math.max(properties.queueCapacity(), 16));
        setName(NAME);
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            // The newest instance owns the root: a context refreshed in one JVM must not keep feeding a queue nobody drains.
            Appender<ILoggingEvent> previous = root.getAppender(NAME);
            if (previous != null) {
                root.detachAppender(previous);
                previous.stop();
            }
            setContext(context);
            start();
            root.addAppender(this);
        }
    }

    /**
     * Capture is off for the calling thread while {@code work} runs: the store's own writes.
     */
    static void muted(Runnable work) {
        MUTED.set(true);
        try {
            work.run();
        } finally {
            MUTED.set(false);
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!event.getLevel().isGreaterOrEqual(Level.WARN) || MUTED.get()) {
            return;
        }
        Captured line = new Captured(Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel().toString(),
                event.getLoggerName(), event.getThreadName(), LogSignatures.redact(event.getFormattedMessage()),
                LogSignatures.exceptionOf(event.getThrowableProxy()), LogSignatures.traceOf(event.getThrowableProxy()));
        if (queue.offer(line)) {
            captured.incrementAndGet();
        } else {
            dropped.incrementAndGet();
        }
    }

    /**
     * Everything waiting, up to {@code max}, in the order it was logged.
     */
    List<Captured> drain(int max) {
        List<Captured> out = new ArrayList<>();
        queue.drainTo(out, max);
        return out;
    }

    public int waiting() {
        return queue.size();
    }

    public long captured() {
        return captured.get();
    }

    public long dropped() {
        return dropped.get();
    }

    /**
     * One line, already reduced to what will be stored.
     */
    public record Captured(Instant at, String level, String logger, String thread, String message, String exception, String trace) {
    }
}
