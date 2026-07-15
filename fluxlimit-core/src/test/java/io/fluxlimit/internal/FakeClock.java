package io.fluxlimit.internal;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Manually advanced clock for deterministic tests. */
public final class FakeClock implements Clock {

  private final AtomicLong nanos = new AtomicLong();

  @Override
  public long nanoTime() {
    return nanos.get();
  }

  public void advance(Duration duration) {
    nanos.addAndGet(duration.toNanos());
  }
}
