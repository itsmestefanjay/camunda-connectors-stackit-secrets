package io.github.itsmestefanjay.camunda.stackit.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StackitSecretsConfigTest {

  private final Map<String, String> env = new HashMap<>();

  @Test
  void isDisabledWithoutInstanceId() {
    assertThat(StackitSecretsConfig.from(env::get)).isEmpty();
  }

  @Test
  void readsRequiredValuesAndAppliesDefaults() {
    givenRequiredVariables();

    StackitSecretsConfig config = StackitSecretsConfig.from(env::get).orElseThrow();

    assertThat(config.instanceId()).isEqualTo("instance-1");
    assertThat(config.username()).isEqualTo("reader");
    assertThat(config.secretPath()).isEqualTo("connectors");
    assertThat(config.baseUrl()).isEqualTo(StackitSecretsConfig.DEFAULT_BASE_URL);
    assertThat(config.cacheTtl()).isEqualTo(StackitSecretsConfig.DEFAULT_CACHE_TTL);
    assertThat(config.requestTimeout()).isEqualTo(StackitSecretsConfig.DEFAULT_REQUEST_TIMEOUT);
  }

  @Test
  void namesEveryMissingVariableAtOnce() {
    env.put(StackitSecretsConfig.INSTANCE_ID, "instance-1");

    assertThatThrownBy(() -> StackitSecretsConfig.from(env::get))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(StackitSecretsConfig.USERNAME)
        .hasMessageContaining(StackitSecretsConfig.PASSWORD)
        .hasMessageContaining(StackitSecretsConfig.SECRET_PATH);
  }

  @Test
  void treatsBlankValuesAsMissing() {
    givenRequiredVariables();
    env.put(StackitSecretsConfig.PASSWORD, "  ");

    assertThatThrownBy(() -> StackitSecretsConfig.from(env::get))
        .hasMessageContaining(StackitSecretsConfig.PASSWORD);
  }

  @Test
  void parsesOverrides() {
    givenRequiredVariables();
    env.put(StackitSecretsConfig.BASE_URL, "http://localhost:8200/");
    env.put(StackitSecretsConfig.CACHE_TTL, "PT30S");
    env.put(StackitSecretsConfig.REQUEST_TIMEOUT, "PT2S");
    env.put(StackitSecretsConfig.SECRET_PATH, "/team/connectors/");

    StackitSecretsConfig config = StackitSecretsConfig.from(env::get).orElseThrow();

    assertThat(config.baseUrl()).isEqualTo(URI.create("http://localhost:8200"));
    assertThat(config.cacheTtl()).isEqualTo(Duration.ofSeconds(30));
    assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(config.secretPath()).isEqualTo("team/connectors");
  }

  @Test
  void rejectsAnInvalidDuration() {
    givenRequiredVariables();
    env.put(StackitSecretsConfig.CACHE_TTL, "5 minutes");

    assertThatThrownBy(() -> StackitSecretsConfig.from(env::get))
        .hasMessageContaining(StackitSecretsConfig.CACHE_TTL)
        .hasMessageContaining("ISO-8601");
  }

  @Test
  void neverPrintsThePassword() {
    givenRequiredVariables();

    String text = StackitSecretsConfig.from(env::get).orElseThrow().toString();

    assertThat(text).doesNotContain("s3cr3t").contains("password=***");
  }

  private void givenRequiredVariables() {
    env.put(StackitSecretsConfig.INSTANCE_ID, "instance-1");
    env.put(StackitSecretsConfig.USERNAME, "reader");
    env.put(StackitSecretsConfig.PASSWORD, "s3cr3t");
    env.put(StackitSecretsConfig.SECRET_PATH, "connectors");
  }
}
