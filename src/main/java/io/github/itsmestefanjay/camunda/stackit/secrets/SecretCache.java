package io.github.itsmestefanjay.camunda.stackit.secrets;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SecretCache {

  private static final Logger LOG = LoggerFactory.getLogger(SecretCache.class);

  private final Supplier<Map<String, String>> loader;
  private final Duration ttl;
  private final Clock clock;
  private final ReentrantLock reloadLock = new ReentrantLock();

  private volatile Snapshot snapshot;

  SecretCache(Supplier<Map<String, String>> loader, Duration ttl, Clock clock) {
    this.loader = loader;
    this.ttl = ttl;
    this.clock = clock;
  }

  Optional<String> get(String name) {
    return Optional.ofNullable(current().values().get(name));
  }

  private Snapshot current() {
    Snapshot cached = snapshot;
    if (isFresh(cached)) {
      return cached;
    }
    reloadLock.lock();
    try {
      Snapshot latest = snapshot;
      return isFresh(latest) ? latest : reload(latest); // re-check: another thread may have reloaded
    } finally {
      reloadLock.unlock();
    }
  }

  private boolean isFresh(Snapshot candidate) {
    return candidate != null && clock.instant().isBefore(candidate.loadedAt().plus(ttl));
  }

  private Snapshot reload(Snapshot previous) {
    try {
      snapshot = new Snapshot(loader.get(), clock.instant());
    } catch (StackitSecretsException e) {
      if (previous == null) {
        throw e;
      }
      LOG.warn("Could not refresh secrets, serving values loaded at {}: {}", previous.loadedAt(), e.getMessage());
      snapshot = new Snapshot(previous.values(), clock.instant()); // retry after next TTL, not per call
    }
    return snapshot;
  }

  private record Snapshot(Map<String, String> values, Instant loadedAt) {}
}
