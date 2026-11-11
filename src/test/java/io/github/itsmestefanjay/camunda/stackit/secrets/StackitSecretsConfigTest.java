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
  void test_config_is_disabled_without_instance_id_as_expected() {
    // when
    var config = StackitSecretsConfig.from(env::get);

    // then
    assertThat(config).as("config").isEmpty();
  }

  @Test
  void test_config_reads_required_values_and_defaults_as_expected() {
    // given
    givenRequiredVariables();

    // when
    StackitSecretsConfig config = StackitSecretsConfig.from(env::get).orElseThrow();

    // then
    assertThat(config.instanceId()).as("instance id").isEqualTo("instance-1");
    assertThat(config.username()).as("username").isEqualTo("reader");
    assertThat(config.secretPath()).as("secret path").isEqualTo("connectors");
    assertThat(config.baseUrl()).as("base url").isEqualTo(StackitSecretsConfig.DEFAULT_BASE_URL);
    assertThat(config.cacheTtl()).as("cache ttl").isEqualTo(StackitSecretsConfig.DEFAULT_CACHE_TTL);
    assertThat(config.requestTimeout())
        .as("request timeout")
        .isEqualTo(StackitSecretsConfig.DEFAULT_REQUEST_TIMEOUT);
  }

  @Test
  void test_config_names_every_missing_variable_as_expected() {
    // given
    env.put(StackitSecretsConfig.INSTANCE_ID, "instance-1");

    // when / then
    assertThatThrownBy(() -> StackitSecretsConfig.from(env::get))
        .as("missing variables failure")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(StackitSecretsConfig.USERNAME)
        .hasMessageContaining(StackitSecretsConfig.PASSWORD)
        .hasMessageContaining(StackitSecretsConfig.SECRET_PATH);
  }

  @Test
  void test_config_treats_blank_values_as_missing_as_expected() {
    // given
    givenRequiredVariables();
    env.put(StackitSecretsConfig.PASSWORD, "  ");

    // when / then
    assertThatThrownBy(() -> StackitSecretsConfig.from(env::get))
        .as("blank password failure")
        .hasMessageContaining(StackitSecretsConfig.PASSWORD);
  }

  @Test
  void test_config_parses_overrides_as_expected() {
    // given
    givenRequiredVariables();
    env.put(StackitSecretsConfig.BASE_URL, "http://localhost:8200/");
    env.put(StackitSecretsConfig.CACHE_TTL, "PT30S");
    env.put(StackitSecretsConfig.REQUEST_TIMEOUT, "PT2S");
    env.put(StackitSecretsConfig.SECRET_PATH, "/team/connectors/");


    // when
    StackitSecretsConfig config = StackitSecretsConfig.from(env::get).orElseThrow();

    // then
    assertThat(config.baseUrl()).as("base url").isEqualTo(URI.create("http://localhost:8200"));
    assertThat(config.cacheTtl()).as("cache ttl").isEqualTo(Duration.ofSeconds(30));
    assertThat(config.requestTimeout()).as("request timeout").isEqualTo(Duration.ofSeconds(2));
    assertThat(config.secretPath()).as("secret path").isEqualTo("team/connectors");
  }

  @Test
  void test_config_rejects_invalid_duration_as_expected() {
    // given
    givenRequiredVariables();
    env.put(StackitSecretsConfig.CACHE_TTL, "5 minutes");

    // when / then
    assertThatThrownBy(() -> StackitSecretsConfig.from(env::get))
        .as("invalid duration failure")
        .hasMessageContaining(StackitSecretsConfig.CACHE_TTL)
        .hasMessageContaining("ISO-8601");
  }

  @Test
  void test_config_never_prints_password_as_expected() {
    // given
    givenRequiredVariables();

    // when
    String text = StackitSecretsConfig.from(env::get).orElseThrow().toString();

    // then
    assertThat(text).as("config text").doesNotContain("s3cr3t").contains("password=***");
  }

  private void givenRequiredVariables() {
    env.put(StackitSecretsConfig.INSTANCE_ID, "instance-1");
    env.put(StackitSecretsConfig.USERNAME, "reader");
    env.put(StackitSecretsConfig.PASSWORD, "s3cr3t");
    env.put(StackitSecretsConfig.SECRET_PATH, "connectors");
  }
}
