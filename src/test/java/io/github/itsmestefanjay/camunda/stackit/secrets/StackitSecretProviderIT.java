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

/**
 * Runs the provider against a real Vault set up the way STACKIT Secrets Manager presents itself:
 * a KV v2 engine mounted at the instance ID, and a read-only userpass user.
 */
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
  void resolvesASecretByName() {
    provider = provider(config(USER, PASSWORD, Duration.ofMinutes(5)));

    assertThat(provider.getSecret("API_TOKEN", CONTEXT)).isEqualTo("first");
    assertThat(provider.getSecret("DB_PASSWORD", CONTEXT)).isEqualTo("db-pass");
  }

  @Test
  void returnsNullForANameItDoesNotHold() {
    provider = provider(config(USER, PASSWORD, Duration.ofMinutes(5)));

    assertThat(provider.getSecret("NOT_THERE", CONTEXT)).isNull();
  }

  @Test
  void picksUpARotatedValueAfterTheCacheTtl() throws Exception {
    Duration ttl = Duration.ofMinutes(5);
    provider = provider(config(USER, PASSWORD, ttl));
    assertThat(provider.getSecret("ROTATED", CONTEXT)).isEqualTo("old");

    vault("kv", "patch", "-mount=" + INSTANCE_ID, PATH, "ROTATED=new");
    assertThat(provider.getSecret("ROTATED", CONTEXT)).isEqualTo("old");

    cacheClock.advance(ttl);
    assertThat(provider.getSecret("ROTATED", CONTEXT)).isEqualTo("new");
  }

  @Test
  void logsInAgainBeforeTheTokenExpires() throws Exception {
    provider = provider(config(USER, PASSWORD, Duration.ZERO));
    assertThat(provider.getSecret("DB_PASSWORD", CONTEXT)).isEqualTo("db-pass");

    Thread.sleep(Duration.ofSeconds(5)); // the 4s token from the first login has expired

    assertThat(provider.getSecret("DB_PASSWORD", CONTEXT)).isEqualTo("db-pass");
  }

  @Test
  void reportsAFailedLoginWithoutTheSecret() {
    provider = provider(config(USER, "wrong-password", Duration.ofMinutes(5)));

    assertThatThrownBy(() -> provider.getSecret("API_TOKEN", CONTEXT))
        .isInstanceOf(StackitSecretsException.class)
        .hasMessageContaining("Login as 'reader' failed with HTTP 400")
        .hasMessageNotContaining("wrong-password");
  }

  @Test
  void resolvesNothingWhenDisabled() {
    provider = new StackitSecretProvider(Optional.empty());

    assertThat(provider.getSecret("API_TOKEN", CONTEXT)).isNull();
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
