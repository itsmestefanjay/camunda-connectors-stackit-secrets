package io.github.itsmestefanjay.camunda.stackit.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.connector.api.secret.SecretContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Optional check against a real STACKIT Secrets Manager instance, configured exactly like the
 * runtime: through the {@code STACKIT_SECRETS_*} variables. Skipped unless they are set.
 *
 * <p>Expects the secret at {@code STACKIT_SECRETS_PATH} to hold a key {@code LIVE_CHECK}.
 */
@EnabledIfEnvironmentVariable(named = "STACKIT_SECRETS_INSTANCE_ID", matches = ".+")
class StackitSecretProviderLiveIT {

  @Test
  void resolvesASecretFromStackit() {
    try (StackitSecretProvider provider = new StackitSecretProvider()) {
      SecretContext context = new SecretContext("<default>", "live-check");

      assertThat(provider.getSecret("LIVE_CHECK", context)).isNotBlank();
      assertThat(provider.getSecret("NOT_THERE", context)).isNull();
    }
  }
}
