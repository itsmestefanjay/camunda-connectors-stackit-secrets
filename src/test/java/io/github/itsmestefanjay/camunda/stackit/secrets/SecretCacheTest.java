package io.github.itsmestefanjay.camunda.stackit.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SecretCacheTest {

  private static final Duration TTL = Duration.ofMinutes(5);

  private final MutableClock clock = new MutableClock();
  private final ScriptedLoader loader = new ScriptedLoader();
  private final SecretCache cache = new SecretCache(loader, TTL, clock);

  @Test
  void loadsOnceWithinTheTtl() {
    loader.thenReturn(Map.of("TOKEN", "a"));

    assertThat(cache.get("TOKEN")).contains("a");
    clock.advance(TTL.minusSeconds(1));
    assertThat(cache.get("TOKEN")).contains("a");

    assertThat(loader.calls).isEqualTo(1);
  }

  @Test
  void reloadsAfterTheTtl() {
    loader.thenReturn(Map.of("TOKEN", "a")).thenReturn(Map.of("TOKEN", "b"));

    cache.get("TOKEN");
    clock.advance(TTL);

    assertThat(cache.get("TOKEN")).contains("b");
  }

  @Test
  void returnsEmptyForAnUnknownName() {
    loader.thenReturn(Map.of("TOKEN", "a"));

    assertThat(cache.get("OTHER")).isEmpty();
  }

  @Test
  void servesTheLastValuesWhenAReloadFails() {
    loader.thenReturn(Map.of("TOKEN", "a")).thenFail();

    cache.get("TOKEN");
    clock.advance(TTL);

    assertThat(cache.get("TOKEN")).contains("a");
  }

  @Test
  void waitsAnotherTtlBeforeRetryingAfterAFailure() {
    loader.thenReturn(Map.of("TOKEN", "a")).thenFail().thenReturn(Map.of("TOKEN", "b"));

    cache.get("TOKEN");
    clock.advance(TTL);
    cache.get("TOKEN"); // fails, keeps "a"
    clock.advance(TTL.minusSeconds(1));
    assertThat(cache.get("TOKEN")).contains("a");

    clock.advance(Duration.ofSeconds(1));
    assertThat(cache.get("TOKEN")).contains("b");
  }

  @Test
  void failsWhenTheFirstLoadFails() {
    loader.thenFail();

    assertThatThrownBy(() -> cache.get("TOKEN")).isInstanceOf(StackitSecretsException.class);
  }

  /** Answers each load with the next scripted result. */
  private static final class ScriptedLoader implements Supplier<Map<String, String>> {

    private final Deque<Supplier<Map<String, String>>> results = new ArrayDeque<>();
    int calls;

    ScriptedLoader thenReturn(Map<String, String> values) {
      results.add(() -> values);
      return this;
    }

    ScriptedLoader thenFail() {
      results.add(
          () -> {
            throw new StackitSecretsException("Secrets Manager unavailable");
          });
      return this;
    }

    @Override
    public Map<String, String> get() {
      calls++;
      return results.removeFirst().get();
    }
  }
}
