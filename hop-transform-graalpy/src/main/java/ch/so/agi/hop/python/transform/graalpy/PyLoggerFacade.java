package ch.so.agi.hop.python.transform.graalpy;

import org.apache.hop.core.logging.ILogChannel;
import org.graalvm.polyglot.HostAccess.Export;

public final class PyLoggerFacade {
  private final ILogChannel log;

  public PyLoggerFacade(ILogChannel log) {
    this.log = log;
  }

  @Export
  public void info(String message) {
    log.logBasic(message);
  }

  @Export
  public void warn(String message) {
    log.logMinimal(message);
  }

  @Export
  public void error(String message) {
    log.logError(message);
  }
}
