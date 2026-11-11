package io.github.itsmestefanjay.camunda.stackit.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.connector.api.secret.SecretContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.vault.VaultContainer;

@Testcontainers
class StackitSecretProviderIT {

  private static final String INSTANCE_ID = "6f1b2c3d-instance";
  private static final String PATH = "connectors";
  private static final String USER = "reader";
  private static final String PASSWORD = "reader-password";
  private static final String READ_ONLY_POLICY =
      "path \"%s/data/*\" { capabilities = [\"read\"] }".formatted(INSTANCE_ID);
  private static final SecretContext CONTEXT = new SecretContext("<default>", "process");

  @Container
  static final VaultContainer<?> VAULT =
      new VaultContainer<>("hashicorp/vault:1.21.4")
          .withVaultToken("root")
          .withEnv("VAULT_ADDR", "http://127.0.0.1:8200")
          .withEnv("VAULT_TOKEN", "root")
          .withCopyToContainer(Transferable.of(READ_ONLY_POLICY), "/tmp/reader.hcl")
          .withInitCommand(
              "secrets enable -path=" + INSTANCE_ID + " kv-v2",
              "auth enable userpass",
              "policy write reader /tmp/reader.hcl",
              "write auth/userpass/users/" + USER + " password=" + PASSWORD + " policies=reader token_ttl=4s token_max_ttl=1h",
              "kv put -mount=" + INSTANCE_ID + " " + PATH + " API_TOKEN=first DB_PASSWORD=db-pass ROTATED=old");

  private final MutableClock cacheClock = new MutableClock();
  private StackitSecretProvider provider;

  @AfterEach
  void closeProvider() {
    if (provider != null) {
      provider.close();
    }
  }

  @Test
  void test_provider_resolves_secret_by_name_as_expected() {
    // given
    provider = provider(config(USER, PASSWORD, Duration.ofMinutes(5)));

    // when
    var apiToken = provider.getSecret("API_TOKEN", CONTEXT);
    var dbPassword = provider.getSecret("DB_PASSWORD", CONTEXT);

    // then
    assertThat(apiToken).as("api token").isEqualTo("first");
    assertThat(dbPassword).as("db password").isEqualTo("db-pass");
  }

  @Test
  void test_provider_returns_null_for_unknown_name_as_expected() {
    // given
    provider = provider(config(USER, PASSWORD, Duration.ofMinutes(5)));

    // when
    var value = provider.getSecret("NOT_THERE", CONTEXT);

    // then
    assertThat(value).as("unknown secret").isNull();
  }

  @Test
  void test_provider_picks_up_rotated_value_after_ttl_as_expected() throws Exception {
    // given
    Duration ttl = Duration.ofMinutes(5);
    provider = provider(config(USER, PASSWORD, ttl));
    var initial = provider.getSecret("ROTATED", CONTEXT);

    // when
    vault("kv", "patch", "-mount=" + INSTANCE_ID, PATH, "ROTATED=new");
    var cached = provider.getSecret("ROTATED", CONTEXT);
    cacheClock.advance(ttl);
    var refreshed = provider.getSecret("ROTATED", CONTEXT);

    // then
    assertThat(initial).as("initial value").isEqualTo("old");
    assertThat(cached).as("cached value").isEqualTo("old");
    assertThat(refreshed).as("refreshed value").isEqualTo("new");
  }

  @Test
  void test_provider_logs_in_again_before_token_expiry_as_expected() throws Exception {
    // given
    provider = provider(config(USER, PASSWORD, Duration.ZERO));
    var initial = provider.getSecret("DB_PASSWORD", CONTEXT);

    // when
    Thread.sleep(Duration.ofSeconds(5)); // 4s token expired
    var afterExpiry = provider.getSecret("DB_PASSWORD", CONTEXT);

    // then
    assertThat(initial).as("initial value").isEqualTo("db-pass");
    assertThat(afterExpiry).as("value after expiry").isEqualTo("db-pass");
  }

  @Test
  void test_provider_reports_failed_login_without_password_as_expected() {
    // given
    provider = provider(config(USER, "wrong-password", Duration.ofMinutes(5)));

    // when / then
    assertThatThrownBy(() -> provider.getSecret("API_TOKEN", CONTEXT))
        .as("login failure")
        .isInstanceOf(StackitSecretsException.class)
        .hasMessageContaining("Login as 'reader' failed with HTTP 400")
        .hasMessageNotContaining("wrong-password");
  }

  @Test
  void test_provider_resolves_nothing_when_disabled_as_expected() {
    // given
    provider = new StackitSecretProvider(Optional.empty());

    // when
    var value = provider.getSecret("API_TOKEN", CONTEXT);

    // then
    assertThat(value).as("disabled secret").isNull();
  }

  private StackitSecretProvider provider(StackitSecretsConfig config) {
    SecretsManagerClient client =
        new SecretsManagerClient(config, HttpClient.newHttpClient(), new ObjectMapper(), Clock.systemUTC());
    return new StackitSecretProvider(Optional.of(config), client, cacheClock);
  }

  private static StackitSecretsConfig config(String user, String password, Duration cacheTtl) {
    return new StackitSecretsConfig(
        URI.create(VAULT.getHttpHostAddress()),
        INSTANCE_ID,
        user,
        password,
        PATH,
        cacheTtl,
        Duration.ofSeconds(5));
  }

  private static void vault(String... command) throws Exception {
    String[] full = new String[command.length + 1];
    full[0] = "vault";
    System.arraycopy(command, 0, full, 1, command.length);
    var result = VAULT.execInContainer(full);
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }
}
