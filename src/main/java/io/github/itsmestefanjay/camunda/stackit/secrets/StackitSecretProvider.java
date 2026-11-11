package io.github.itsmestefanjay.camunda.stackit.secrets;

import io.camunda.connector.api.secret.SecretContext;
import io.camunda.connector.api.secret.SecretProvider;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves {@code {{secrets.NAME}}} from STACKIT Secrets Manager; disabled without {@code STACKIT_SECRETS_INSTANCE_ID}. */
public final class StackitSecretProvider implements SecretProvider, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(StackitSecretProvider.class);

  private final SecretsManagerClient client; // null when disabled
  private final SecretCache cache; // null when disabled

  /** ServiceLoader entry point; configured from the environment. */
  public StackitSecretProvider() {
    this(StackitSecretsConfig.fromEnvironment());
  }

  StackitSecretProvider(Optional<StackitSecretsConfig> config) {
    this(config, config.map(SecretsManagerClient::create).orElse(null), Clock.systemUTC());
  }

  StackitSecretProvider(Optional<StackitSecretsConfig> config, SecretsManagerClient client, Clock cacheClock) {
    if (config.isEmpty()) {
      LOG.info("STACKIT secret provider disabled: {} is not set", StackitSecretsConfig.INSTANCE_ID);
      this.client = null;
      this.cache = null;
      return;
    }
    StackitSecretsConfig settings = config.get();
    LOG.info(
        "STACKIT secret provider enabled: instance {}, path '{}', cache TTL {}",
        settings.instanceId(),
        settings.secretPath(),
        settings.cacheTtl());
    this.client = client;
    this.cache = new SecretCache(client::readSecrets, settings.cacheTtl(), cacheClock);
  }

  /** {@code null} for unknown names so the runtime asks its next provider. */
  @Override
  public String getSecret(String name, SecretContext context) {
    return cache == null ? null : cache.get(name).orElse(null);
  }

  @Override
  public void close() {
    if (client != null) {
      client.close();
    }
  }
}
