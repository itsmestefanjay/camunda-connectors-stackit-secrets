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

/**
 * Holds the last snapshot of secrets and reloads it once it is older than the TTL.
 *
 * <p>Only one thread reloads at a time; the others wait for it instead of hitting Secrets Manager
 * in parallel. If a reload fails while an older snapshot exists, the old values keep being served
 * and the failure is logged, so a short Secrets Manager outage does not fail running processes.
 */
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

  /**
   * Returns the value of a secret.
   *
   * @return the value, or empty if Secrets Manager does not hold a secret with that name
   * @throws StackitSecretsException if nothing was ever loaded and loading fails now
   */
  Optional<String> get(String name) {
    return Optional.ofNullable(current().values().get(name));
  }

  private Snapshot current() {
    Snapshot cached = snapshot;
    if (cached != null && !cached.isExpired(clock.instant(), ttl)) {
      return cached;
    }
    reloadLock.lock();
    try {
      Snapshot latest = snapshot;
      if (latest != null && !latest.isExpired(clock.instant(), ttl)) {
        return latest; // another thread reloaded while this one waited
      }
      return reload(latest);
    } finally {
      reloadLock.unlock();
    }
  }

  private Snapshot reload(Snapshot previous) {
    try {
      Snapshot fresh = new Snapshot(loader.get(), clock.instant());
      snapshot = fresh;
      return fresh;
    } catch (StackitSecretsException e) {
      if (previous == null) {
        throw e;
      }
      LOG.warn("Could not refresh secrets, serving values loaded at {}: {}", previous.loadedAt(), e.getMessage());
      // retry after another TTL instead of on every call during an outage
      Snapshot retained = new Snapshot(previous.values(), clock.instant());
      snapshot = retained;
      return retained;
    }
  }

  private record Snapshot(Map<String, String> values, Instant loadedAt) {

    boolean isExpired(Instant now, Duration ttl) {
      return !now.isBefore(loadedAt.plus(ttl));
    }
  }
}
