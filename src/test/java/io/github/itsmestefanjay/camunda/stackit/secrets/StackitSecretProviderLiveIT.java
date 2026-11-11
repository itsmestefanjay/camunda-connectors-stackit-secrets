package io.github.itsmestefanjay.camunda.stackit.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.connector.api.secret.SecretContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real STACKIT instance; runs only with {@code STACKIT_SECRETS_*} set and a {@code LIVE_CHECK} key.
 */
@EnabledIfEnvironmentVariable(named = "STACKIT_SECRETS_INSTANCE_ID", matches = ".+")
class StackitSecretProviderLiveIT {

  @Test
  void test_provider_resolves_secret_from_stackit_as_expected() {
    // given
    try (StackitSecretProvider provider = new StackitSecretProvider()) {
      SecretContext context = new SecretContext("<default>", "live-check");

      // when
      var known = provider.getSecret("LIVE_CHECK", context);
      var unknown = provider.getSecret("NOT_THERE", context);

      // then
      assertThat(known).as("live secret").isNotBlank();
      assertThat(unknown).as("unknown secret").isNull();
    }
  }
}
