package ch.so.agi.hop.python.transform.graalpy;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/** One UTF-8 byte budget shared by the facade and both guest streams. */
public final class PythonSessionLog {
  private final long limit;
  private final BiConsumer<String, String> sink;
  private long count;
  private boolean truncated;

  public PythonSessionLog(long limit, BiConsumer<String, String> sink) {
    this.limit = limit;
    this.sink = sink;
  }

  private synchronized boolean reserve(int bytes) {
    if (truncated) return false;
    if (limit > 0 && bytes > limit - count) {
      if (!truncated) {
        truncated = true;
        sink.accept("WARN", "Python log limit reached; further output suppressed.");
      }
      return false;
    }
    count += bytes;
    return true;
  }

  public synchronized void write(String level, String message) {
    if (reserve(message.getBytes(StandardCharsets.UTF_8).length)) sink.accept(level, message);
  }

  public OutputStream stream(String level) {
    return new OutputStream() {
      private final ByteArrayOutputStream line = new ByteArrayOutputStream();

      @Override
      public synchronized void write(int value) {
        if (!reserve(1)) {
          flush();
          return;
        }
        if (value == '\n') flush();
        else {
          line.write(value);
          if (line.size() >= 4096) flush();
        }
      }

      @Override
      public synchronized void flush() {
        if (line.size() != 0) {
          sink.accept(level, line.toString(StandardCharsets.UTF_8));
          line.reset();
        }
      }

      @Override
      public void close() {
        flush();
      }
    };
  }
}
