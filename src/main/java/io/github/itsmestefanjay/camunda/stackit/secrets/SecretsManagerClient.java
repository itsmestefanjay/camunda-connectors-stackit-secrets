package io.github.itsmestefanjay.camunda.stackit.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads one KV v2 secret from STACKIT Secrets Manager, using its Vault-compatible HTTP API.
 *
 * <p>Logs in with userpass and keeps the client token until shortly before its lease ends, then
 * logs in again. A token revoked early is detected by a 403 and replaced once per read.
 */
final class SecretsManagerClient implements AutoCloseable {

  /** Renew this long before the token lease ends, so a token never expires mid-request. */
  static final Duration RENEWAL_MARGIN = Duration.ofSeconds(30);

  private final StackitSecretsConfig config;
  private final HttpClient http;
  private final ObjectMapper json;
  private final Clock clock;

  private String token;
  private Instant tokenRenewAt = Instant.MIN;

  SecretsManagerClient(StackitSecretsConfig config, HttpClient http, ObjectMapper json, Clock clock) {
    this.config = config;
    this.http = http;
    this.json = json;
    this.clock = clock;
  }

  static SecretsManagerClient create(StackitSecretsConfig config) {
    HttpClient http =
        HttpClient.newBuilder()
            .connectTimeout(config.requestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return new SecretsManagerClient(config, http, new ObjectMapper(), Clock.systemUTC());
  }

  /**
   * Reads all key/value pairs stored at the configured secret path.
   *
   * @return the secret's keys and values; values that are not JSON strings are returned as JSON
   * @throws StackitSecretsException if the secret cannot be read
   */
  synchronized Map<String, String> readSecrets() {
    HttpResponse<String> response = send(readRequest(currentToken()));
    if (response.statusCode() == 403) {
      invalidateToken();
      response = send(readRequest(currentToken()));
    }
    return switch (response.statusCode()) {
      case 200 -> parseSecret(response.body());
      case 404 ->
          throw new StackitSecretsException(
              "No secret at '%s' in instance %s".formatted(config.secretPath(), config.instanceId()));
      default -> throw failure("Reading secret '" + config.secretPath() + "'", response);
    };
  }

  @Override
  public void close() {
    http.close();
  }

  private String currentToken() {
    if (token == null || !clock.instant().isBefore(tokenRenewAt)) {
      login();
    }
    return token;
  }

  private void invalidateToken() {
    token = null;
  }

  private void login() {
    String body = json.createObjectNode().put("password", config.password()).toString();
    HttpRequest request =
        HttpRequest.newBuilder(uri("/v1/auth/userpass/login/" + config.username()))
            .timeout(config.requestTimeout())
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = send(request);
    if (response.statusCode() != 200) {
      throw failure("Login as '" + config.username() + "'", response);
    }

    JsonNode auth = readTree(response.body()).path("auth");
    String clientToken = auth.path("client_token").asText(null);
    if (clientToken == null || clientToken.isBlank()) {
      throw new StackitSecretsException("Login response did not contain a client token");
    }
    long leaseSeconds = auth.path("lease_duration").asLong(0);
    token = clientToken;
    tokenRenewAt = renewalTime(leaseSeconds);
  }

  private Instant renewalTime(long leaseSeconds) {
    if (leaseSeconds <= 0) {
      return Instant.MAX; // a token without a lease does not expire
    }
    Duration lease = Duration.ofSeconds(leaseSeconds);
    Duration margin = lease.compareTo(RENEWAL_MARGIN.multipliedBy(2)) > 0 ? RENEWAL_MARGIN : lease.dividedBy(2);
    return clock.instant().plus(lease).minus(margin);
  }

  private HttpRequest readRequest(String clientToken) {
    return HttpRequest.newBuilder(uri("/v1/" + config.instanceId() + "/data/" + config.secretPath()))
        .timeout(config.requestTimeout())
        .header("X-Vault-Token", clientToken)
        .GET()
        .build();
  }

  private Map<String, String> parseSecret(String body) {
    JsonNode data = readTree(body).path("data").path("data");
    if (!data.isObject()) {
      throw new StackitSecretsException(
          "Secret '" + config.secretPath() + "' is not a KV v2 secret with key/value data");
    }
    Map<String, String> secrets = new LinkedHashMap<>();
    data.properties()
        .forEach(entry -> secrets.put(entry.getKey(), asSecretValue(entry.getValue())));
    return Map.copyOf(secrets);
  }

  private static String asSecretValue(JsonNode value) {
    return value.isTextual() ? value.asText() : value.toString();
  }

  private JsonNode readTree(String body) {
    try {
      return json.readTree(body);
    } catch (IOException e) {
      throw new StackitSecretsException("Secrets Manager returned invalid JSON", e);
    }
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new StackitSecretsException("Cannot reach Secrets Manager at " + config.baseUrl(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new StackitSecretsException("Interrupted while calling Secrets Manager", e);
    }
  }

  /** Builds an error from the status and Vault's {@code errors} array; never echoes a secret. */
  private StackitSecretsException failure(String action, HttpResponse<String> response) {
    String errors = "";
    try {
      JsonNode node = json.readTree(response.body()).path("errors");
      if (node.isArray() && !node.isEmpty()) {
        errors = ": " + node;
      }
    } catch (IOException | RuntimeException ignored) {
      // the status code alone still says what happened
    }
    return new StackitSecretsException(
        "%s failed with HTTP %d%s".formatted(action, response.statusCode(), errors));
  }

  private URI uri(String path) {
    return URI.create(config.baseUrl() + path);
  }
}
