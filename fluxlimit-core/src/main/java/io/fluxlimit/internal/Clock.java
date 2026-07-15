package io.fluxlimit.internal;

/**
 * Monotonic nanosecond time source, replaceable in tests.
 *
 * <p>Everything under {@code io.fluxlimit.internal} is not public API and may change without
 * notice.
 */
@FunctionalInterface
public interface Clock {

  long nanoTime();

  static Clock system() {
    return SystemClock.INSTANCE;
  }

  enum SystemClock implements Clock {
    INSTANCE;

    @Override
    public long nanoTime() {
      return System.nanoTime();
    }
  }
}
