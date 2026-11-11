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
  void test_cache_loads_once_within_ttl_as_expected() {
    // given
    loader.thenReturn(Map.of("TOKEN", "a"));

    // when
    var first = cache.get("TOKEN");
    clock.advance(TTL.minusSeconds(1));
    var second = cache.get("TOKEN");

    // then
    assertThat(first).as("first value").contains("a");
    assertThat(second).as("second value").contains("a");
    assertThat(loader.calls).as("load calls").isEqualTo(1);
  }

  @Test
  void test_cache_reloads_after_ttl_as_expected() {
    // given
    loader.thenReturn(Map.of("TOKEN", "a")).thenReturn(Map.of("TOKEN", "b"));
    cache.get("TOKEN");
    clock.advance(TTL);

    // when
    var value = cache.get("TOKEN");

    // then
    assertThat(value).as("reloaded value").contains("b");
  }

  @Test
  void test_cache_returns_empty_for_unknown_name_as_expected() {
    // given
    loader.thenReturn(Map.of("TOKEN", "a"));

    // when
    var value = cache.get("OTHER");

    // then
    assertThat(value).as("unknown value").isEmpty();
  }

  @Test
  void test_cache_serves_stale_values_when_reload_fails_as_expected() {
    // given
    loader.thenReturn(Map.of("TOKEN", "a")).thenFail();
    cache.get("TOKEN");
    clock.advance(TTL);

    // when
    var value = cache.get("TOKEN");

    // then
    assertThat(value).as("stale value").contains("a");
  }

  @Test
  void test_cache_waits_another_ttl_after_failure_as_expected() {
    // given
    loader.thenReturn(Map.of("TOKEN", "a")).thenFail().thenReturn(Map.of("TOKEN", "b"));
    cache.get("TOKEN");
    clock.advance(TTL);
    cache.get("TOKEN"); // fails, keeps "a"
    clock.advance(TTL.minusSeconds(1));

    // when
    var beforeRetry = cache.get("TOKEN");
    clock.advance(Duration.ofSeconds(1));
    var afterRetry = cache.get("TOKEN");

    // then
    assertThat(beforeRetry).as("value before retry").contains("a");
    assertThat(afterRetry).as("value after retry").contains("b");
  }

  @Test
  void test_cache_fails_when_first_load_fails_as_expected() {
    // given
    loader.thenFail();

    // when / then
    assertThatThrownBy(() -> cache.get("TOKEN"))
        .as("first load failure")
        .isInstanceOf(StackitSecretsException.class);
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
