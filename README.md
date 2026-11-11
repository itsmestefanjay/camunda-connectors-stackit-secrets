# camunda-connectors-stackit-secrets
Camunda 8 connector secret provider for STACKIT Secrets Manager (Vault-compatible). Resolves {{secrets.*}} in the Camunda Connectors runtime directly from STACKIT, without Kubernetes Secrets or External Secrets Operator. Java SPI with token renewal and caching for Camunda Self-Managed and hybrid runtimes on the German sovereign cloud.

> Use at your own risk. This is an independent project, not affiliated with or endorsed by Camunda or STACKIT.

## What it does

A `SecretProvider` (registered via `META-INF/services`) that resolves `{{secrets.NAME}}` in connector properties from one KV v2 secret in STACKIT Secrets Manager. Each key in that secret is one Camunda secret name.

Camunda 8.10 orchestration-cluster `camunda.secrets.*` stores support only file, AWS and GCP. This provider is the route for STACKIT.

The `{{secrets.X}}` syntax is fully supported in 8.10. Only the bare `secrets.X` form without braces is deprecated.

Resolution order in the 8.10 connector runtime:

1. Spring-bean providers (environment variables with prefix `SECRET_`).
2. SPI providers, including this one.

The first non-null value wins. SPI discovery is on by default; toggle it with `camunda.connector.secretprovider.discovery.enabled`.

## Configuration

All settings are environment variables of the connector runtime.

| Variable                          | Required     | Default                              | Meaning                                                                                                                 |
|-----------------------------------|--------------|--------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `STACKIT_SECRETS_INSTANCE_ID`     | no           | unset                                | Secrets Manager instance ID (also the KV v2 mount). Unset disables the provider; it then returns `null` for every name. |
| `STACKIT_SECRETS_USERNAME`        | when enabled |                                      | Userpass user.                                                                                                          |
| `STACKIT_SECRETS_PASSWORD`        | when enabled |                                      | Password of that user.                                                                                                  |
| `STACKIT_SECRETS_PATH`            | when enabled |                                      | KV v2 path of the secret whose keys are the secret names.                                                               |
| `STACKIT_SECRETS_URL`             | no           | `https://prod.sm.eu01.stackit.cloud` | API endpoint.                                                                                                           |
| `STACKIT_SECRETS_CACHE_TTL`       | no           | `PT5M`                               | ISO-8601 duration; must not be negative.                                                                                |
| `STACKIT_SECRETS_REQUEST_TIMEOUT` | no           | `PT10S`                              | ISO-8601 duration per HTTP request; must be positive.                                                                   |

If the instance ID is set but a required variable is missing, startup fails and the message names every missing variable.

## Quick start

Uses the [STACKIT CLI](https://github.com/stackitcloud/stackit-cli).

Create an instance. The ACL must contain the egress IP of your connector runtime.

```sh
stackit secrets-manager instance create --name camunda-secrets --acl <egress-cidr> -p <project>
```

Create a user. It is read-only by default. Create a separate `--write` user only to seed secrets.

```sh
stackit secrets-manager user create --instance-id <instance-id> -p <project>
```

Seed the secret with curl (using the write user):

```sh
TOKEN=$(curl -s -X POST \
  -H 'Content-Type: application/json' \
  -d '{"password":"<write-user-password>"}' \
  https://prod.sm.eu01.stackit.cloud/v1/auth/userpass/login/<write-user> \
  | jq -r .auth.client_token)

curl -s -X POST \
  -H "X-Vault-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"data":{"MY_API_KEY":"value","DB_PASSWORD":"value"}}' \
  https://prod.sm.eu01.stackit.cloud/v1/<instance-id>/data/<path>
```

Note: send the login with `Content-Type: application/json`. Without it the generated passwords (they contain `%` and `=`) are mangled and you get `400 invalid username or password`.

Then use `{{secrets.MY_API_KEY}}` in a connector property.

## Install

```sh
mvn verify
```

The jar lands in `target/` (about 18 KB). It has no runtime dependencies: connector-core, Jackson and slf4j come from the connectors runtime.

Copy the jar to `/opt/custom` in the connectors bundle or runtime image. The image loads extra jars from there via `-Dloader.path`.

```dockerfile
FROM camunda/connectors-bundle:8.10.x
COPY target/camunda-connectors-stackit-secrets-0.1.0-SNAPSHOT.jar /opt/custom/
```

Environment for the container (Helm values or equivalent):

```yaml
env:
  - name: STACKIT_SECRETS_INSTANCE_ID
    value: "<instance-id>"
  - name: STACKIT_SECRETS_USERNAME
    value: "<read-only-user>"
  - name: STACKIT_SECRETS_PATH
    value: "<path>"
  - name: STACKIT_SECRETS_PASSWORD
    valueFrom:
      secretKeyRef:
        name: stackit-secrets-manager
        key: password
```

Keep the password in a Kubernetes Secret, not in the values file. This is the one credential you still bootstrap outside Secrets Manager.

## Behaviour

- One GET per cache TTL reads the whole secret. Reloads are single-flight: concurrent callers wait for one reload.
- If a reload fails, the last values keep being served and the next retry waits another TTL. The failure is logged as a warning.
- If the very first load fails, the lookup throws.
- The token is renewed before its lease expires. On a 403 the provider logs in once more and retries the read.
- A name the secret does not hold returns `null`, so other providers can answer.
- Values that are not JSON strings are returned as their JSON text.

## Security

- Use a read-only Secrets Manager user.
- Restrict the instance ACL to the runtime's egress IP.
- The password is never logged. `StackitSecretsConfig.toString` redacts it, and error messages only echo Vault's `errors` array.
- Redirects are not followed, so the token cannot leak to another host.
- Repeated failed logins lock out the username and source IP pair for 10 minutes.

## Development

Requires JDK 21, Maven 3.9+ and Docker (integration tests start `hashicorp/vault` with Testcontainers).

```sh
mvn verify
```

`StackitSecretProviderLiveIT` runs against a real instance when the `STACKIT_SECRETS_*` variables are set. The secret at `STACKIT_SECRETS_PATH` must contain a key `LIVE_CHECK`.

## Limits

- One secret path per runtime.
- Userpass authentication only; no AppRole yet.
- Tenant and process context are not used for scoping.
