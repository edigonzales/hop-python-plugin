package ch.so.agi.hop.python.transform.graalpy;

import org.apache.hop.core.logging.ILogChannel;
import org.graalvm.polyglot.HostAccess.Export;

public final class PyLoggerFacade {
  private final PythonSessionLog log;

  public PyLoggerFacade(ILogChannel log) {
    this(
        new PythonSessionLog(
            0,
            (level, message) -> {
              if ("ERROR".equals(level)) log.logError(message);
              else log.logBasic(message);
            }));
  }

  public PyLoggerFacade(PythonSessionLog log) {
    this.log = log;
  }

  @Export
  public void info(String message) {
    log.write("INFO", message);
  }

  @Export
  public void warn(String message) {
    log.write("WARN", message);
  }

  @Export
  public void error(String message) {
    log.write("ERROR", message);
  }
}
