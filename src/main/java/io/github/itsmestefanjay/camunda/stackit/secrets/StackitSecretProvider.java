package io.github.itsmestefanjay.camunda.stackit.secrets;

import io.camunda.connector.api.secret.SecretContext;
import io.camunda.connector.api.secret.SecretProvider;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves {@code {{secrets.NAME}}} in the Camunda Connectors runtime from STACKIT Secrets Manager.
 *
 * <p>Discovered by the runtime through {@link java.util.ServiceLoader}. Each secret name is a key
 * of the KV v2 secret at {@code STACKIT_SECRETS_PATH}. A name Secrets Manager does not hold
 * returns {@code null}, so the runtime moves on to its other providers.
 *
 * <p>Without {@code STACKIT_SECRETS_INSTANCE_ID} the provider stays disabled and resolves nothing,
 * which keeps the jar safe to ship in images that do not use it.
 */
public final class StackitSecretProvider implements SecretProvider, AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(StackitSecretProvider.class);

  private final SecretCache cache;
  private final SecretsManagerClient client;

  /** Used by {@link java.util.ServiceLoader}; reads its configuration from the environment. */
  public StackitSecretProvider() {
    this(StackitSecretsConfig.fromEnvironment());
  }

  StackitSecretProvider(Optional<StackitSecretsConfig> config) {
    this(config, config.map(SecretsManagerClient::create).orElse(null), Clock.systemUTC());
  }

  StackitSecretProvider(Optional<StackitSecretsConfig> config, SecretsManagerClient client, Clock clock) {
    if (config.isEmpty()) {
      LOG.info("STACKIT secret provider disabled: STACKIT_SECRETS_INSTANCE_ID is not set");
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
    this.cache = new SecretCache(client::readSecrets, settings.cacheTtl(), clock);
  }

  @Override
  public String getSecret(String name, SecretContext context) {
    if (cache == null) {
      return null;
    }
    return cache.get(name).orElse(null);
  }

  @Override
  public void close() {
    if (client != null) {
      client.close();
    }
  }
}
