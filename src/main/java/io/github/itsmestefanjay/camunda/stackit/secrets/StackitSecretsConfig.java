package io.github.itsmestefanjay.camunda.stackit.secrets;

import java.net.URI;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Settings for one Secrets Manager instance, read from {@code STACKIT_SECRETS_*} variables.
 *
 * @param baseUrl API endpoint
 * @param instanceId instance ID, also the KV v2 mount
 * @param username userpass user, read-only is enough
 * @param password password of that user
 * @param secretPath KV v2 path whose keys are the secret names
 * @param cacheTtl how long values are served before a reload
 * @param requestTimeout timeout per HTTP request
 */
public record StackitSecretsConfig(
    URI baseUrl,
    String instanceId,
    String username,
    String password,
    String secretPath,
    Duration cacheTtl,
    Duration requestTimeout) {

  static final String BASE_URL = "STACKIT_SECRETS_URL";
  static final String INSTANCE_ID = "STACKIT_SECRETS_INSTANCE_ID";
  static final String USERNAME = "STACKIT_SECRETS_USERNAME";
  static final String PASSWORD = "STACKIT_SECRETS_PASSWORD";
  static final String SECRET_PATH = "STACKIT_SECRETS_PATH";
  static final String CACHE_TTL = "STACKIT_SECRETS_CACHE_TTL";
  static final String REQUEST_TIMEOUT = "STACKIT_SECRETS_REQUEST_TIMEOUT";

  static final URI DEFAULT_BASE_URL = URI.create("https://prod.sm.eu01.stackit.cloud");
  static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);
  static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /** Rejects nulls, a negative TTL and a non-positive timeout; strips slashes off the path. */
  public StackitSecretsConfig {
    Objects.requireNonNull(baseUrl, "baseUrl");
    Objects.requireNonNull(instanceId, "instanceId");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(secretPath, "secretPath");
    Objects.requireNonNull(cacheTtl, "cacheTtl");
    Objects.requireNonNull(requestTimeout, "requestTimeout");
    if (cacheTtl.isNegative()) {
      throw new IllegalArgumentException(CACHE_TTL + " must not be negative");
    }
    if (!requestTimeout.isPositive()) {
      throw new IllegalArgumentException(REQUEST_TIMEOUT + " must be positive");
    }
    secretPath = secretPath.strip().replaceAll("^/+|/+$", "");
  }

  /** Empty if {@code STACKIT_SECRETS_INSTANCE_ID} is unset. */
  public static Optional<StackitSecretsConfig> fromEnvironment() {
    return from(System::getenv);
  }

  /** Throws {@link IllegalStateException} naming every missing variable once enabled. */
  static Optional<StackitSecretsConfig> from(Function<String, String> env) {
    String instanceId = value(env, INSTANCE_ID);
    if (instanceId == null) {
      return Optional.empty();
    }

    List<String> missing = new ArrayList<>();
    String username = required(env, USERNAME, missing);
    String password = required(env, PASSWORD, missing);
    String secretPath = required(env, SECRET_PATH, missing);
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          INSTANCE_ID + " is set, so these variables are required as well: " + missing);
    }

    String baseUrl = value(env, BASE_URL);
    return Optional.of(
        new StackitSecretsConfig(
            baseUrl == null ? DEFAULT_BASE_URL : URI.create(baseUrl.replaceAll("/+$", "")),
            instanceId,
            username,
            password,
            secretPath,
            duration(env, CACHE_TTL, DEFAULT_CACHE_TTL),
            duration(env, REQUEST_TIMEOUT, DEFAULT_REQUEST_TIMEOUT)));
  }

  /** Password redacted. */
  @Override
  public String toString() {
    return ("StackitSecretsConfig[baseUrl=%s, instanceId=%s, username=%s, password=***, "
            + "secretPath=%s, cacheTtl=%s, requestTimeout=%s]")
        .formatted(baseUrl, instanceId, username, secretPath, cacheTtl, requestTimeout);
  }

  private static String value(Function<String, String> env, String name) {
    String value = env.apply(name);
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String required(Function<String, String> env, String name, List<String> missing) {
    String value = value(env, name);
    if (value == null) {
      missing.add(name);
    }
    return value;
  }

  private static Duration duration(Function<String, String> env, String name, Duration fallback) {
    String value = value(env, name);
    if (value == null) {
      return fallback;
    }
    try {
      return Duration.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalStateException(name + " must be an ISO-8601 duration such as PT5M, got '" + value + "'", e);
    }
  }
}
