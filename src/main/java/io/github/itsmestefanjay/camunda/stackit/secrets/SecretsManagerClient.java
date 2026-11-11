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
import java.util.HashMap;
import java.util.Map;

/** Reads one KV v2 secret via the Vault-compatible API; thread-safe through synchronized {@link #readSecrets}. */
final class SecretsManagerClient implements AutoCloseable {

  static final Duration RENEWAL_MARGIN = Duration.ofSeconds(30);

  private static final int OK = 200;
  private static final int FORBIDDEN = 403;
  private static final int NOT_FOUND = 404;

  private final StackitSecretsConfig config;
  private final HttpClient http;
  private final ObjectMapper json;
  private final Clock clock;

  private String token; // null = log in on next read
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
            .followRedirects(HttpClient.Redirect.NEVER) // token must not reach another host
            .build();
    return new SecretsManagerClient(config, http, new ObjectMapper(), Clock.systemUTC());
  }

  /** All keys at the secret path; non-string values as JSON text. */
  synchronized Map<String, String> readSecrets() {
    HttpResponse<String> response = send(readRequest());
    if (response.statusCode() == FORBIDDEN) { // token revoked early
      token = null;
      response = send(readRequest());
    }
    return switch (response.statusCode()) {
      case OK -> parseSecret(response.body());
      case NOT_FOUND ->
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

  private void login() {
    String body = json.createObjectNode().put("password", config.password()).toString();
    HttpRequest request =
        request("/v1/auth/userpass/login/" + config.username())
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = send(request);
    if (response.statusCode() != OK) {
      throw failure("Login as '" + config.username() + "'", response);
    }

    JsonNode auth = readTree(response.body()).path("auth");
    String clientToken = auth.path("client_token").asText("");
    if (clientToken.isBlank()) {
      throw new StackitSecretsException("Login response did not contain a client token");
    }
    token = clientToken;
    tokenRenewAt = renewalTime(auth.path("lease_duration").asLong(0));
  }

  private Instant renewalTime(long leaseSeconds) {
    if (leaseSeconds <= 0) {
      return Instant.MAX; // no lease = no expiry
    }
    Duration lease = Duration.ofSeconds(leaseSeconds);
    Duration margin =
        lease.compareTo(RENEWAL_MARGIN.multipliedBy(2)) > 0 ? RENEWAL_MARGIN : lease.dividedBy(2);
    return clock.instant().plus(lease).minus(margin);
  }

  private HttpRequest readRequest() {
    return request("/v1/" + config.instanceId() + "/data/" + config.secretPath())
        .header("X-Vault-Token", currentToken())
        .GET()
        .build();
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(config.baseUrl() + path)).timeout(config.requestTimeout());
  }

  private Map<String, String> parseSecret(String body) {
    JsonNode data = readTree(body).path("data").path("data");
    if (!data.isObject()) {
      throw new StackitSecretsException(
          "Secret '" + config.secretPath() + "' is not a KV v2 secret with key/value data");
    }
    Map<String, String> secrets = new HashMap<>();
    data.properties().forEach(e -> secrets.put(e.getKey(), asSecretValue(e.getValue())));
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

  private StackitSecretsException failure(String action, HttpResponse<String> response) {
    String errors = "";
    try {
      JsonNode node = json.readTree(response.body()).path("errors");
      if (node.isArray() && !node.isEmpty()) {
        errors = ": " + node;
      }
    } catch (IOException | RuntimeException ignored) {
      // status code is enough
    }
    return new StackitSecretsException("%s failed with HTTP %d%s".formatted(action, response.statusCode(), errors));
  }
}
