# camunda-connectors-stackit-secrets

Camunda 8 connector secret provider for STACKIT Secrets Manager (Vault-compatible). Resolves {{secrets.*}} in the Camunda Connectors runtime directly from STACKIT, without Kubernetes Secrets or External Secrets Operator. Java SPI with token renewal and caching for Camunda Self-Managed and hybrid runtimes on the German sovereign cloud.

> Use at your own risk. Independent project, not affiliated with or endorsed by Camunda or STACKIT.

Camunda 8.10 cluster secret stores support file, AWS and GCP only; this provider covers `{{secrets.*}}` in connectors.

## Requirements

- Camunda 8.10 connectors runtime (bundle image).
- Build: JDK 21, Maven 3.9+.
- Docker, only for integration tests.

## Build

```sh
mvn package -DskipTests   # jar only
mvn verify                # jar with tests
```

Output: `target/camunda-connectors-stackit-secrets-<version>.jar`.

No runtime dependencies. connector-core, Jackson and slf4j are provided by the runtime.

## Configure

Settings are environment variables of the connector runtime.

| Variable                          | Required     | Default                              | Meaning                                                              |
|-----------------------------------|--------------|--------------------------------------|----------------------------------------------------------------------|
| `STACKIT_SECRETS_INSTANCE_ID`     | no           | unset                                | Instance ID, also the KV v2 mount. Unset disables the provider.      |
| `STACKIT_SECRETS_USERNAME`        | when enabled |                                      | Userpass user.                                                       |
| `STACKIT_SECRETS_PASSWORD`        | when enabled |                                      | Password of that user.                                               |
| `STACKIT_SECRETS_PATH`            | when enabled |                                      | KV v2 path of the secret. Its keys are the secret names.             |
| `STACKIT_SECRETS_URL`             | no           | `https://prod.sm.eu01.stackit.cloud` | API endpoint.                                                        |
| `STACKIT_SECRETS_CACHE_TTL`       | no           | `PT5M`                               | ISO-8601 duration. Must not be negative.                             |
| `STACKIT_SECRETS_REQUEST_TIMEOUT` | no           | `PT10S`                              | ISO-8601 duration per HTTP request. Must be positive.                |

Provider is disabled when `STACKIT_SECRETS_INSTANCE_ID` is unset. If it is set but incomplete, startup fails and names every missing variable.

### Prepare STACKIT

1. Create the instance. The ACL must contain the egress IP of the runtime.

   ```sh
   stackit secrets-manager instance create --name camunda-secrets --acl <egress-cidr> -p <project>
   ```

2. Create a read-only user. Create a separate `--write` user only to seed secrets.

   ```sh
   stackit secrets-manager user create --instance-id <instance-id> -p <project>
   ```

3. Seed the secret with the write user. Send the login with `Content-Type: application/json`. Without it, generated passwords (containing `%` and `=`) are mangled and the login fails with `400 invalid username or password`.

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

Each key of the secret is one secret name.

## Run

Copy the jar to `/opt/custom`; the bundle image loads extra jars from there.

```dockerfile
FROM camunda/connectors-bundle:<8.10 tag>
COPY target/camunda-connectors-stackit-secrets-<version>.jar /opt/custom/
```

Kubernetes environment (Helm values or equivalent):

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

Use in a connector property:

```text
{{secrets.MY_API_KEY}}
```

Resolution order: `SECRET_*` environment variables first, then SPI providers including this one. The first non-null value wins.

## Behaviour

- The cache reads the whole secret once per `STACKIT_SECRETS_CACHE_TTL`.
- On a reload failure, stale values are served. If the first load fails, the lookup throws.
- The token is renewed before its lease ends. On 403, the provider logs in once more and retries.
- An unknown name returns `null`.
- Non-string values are returned as JSON text.

## Security

- Use a read-only Secrets Manager user.
- Restrict the instance ACL to the runtime's egress IP.
- The password is never logged.
- Redirects are not followed.

## Development

`mvn verify` runs unit tests and Testcontainers Vault integration tests (`hashicorp/vault`).

The live integration test `StackitSecretProviderLiveIT` runs when `STACKIT_SECRETS_*` is set and the secret has a key `LIVE_CHECK`:

```sh
STACKIT_SECRETS_INSTANCE_ID=... STACKIT_SECRETS_USERNAME=... STACKIT_SECRETS_PASSWORD=... STACKIT_SECRETS_PATH=... mvn verify
```

## Limits

- One secret path per runtime.
- Userpass authentication only. No AppRole.
- Tenant and process context are not used for scoping.

## License

Apache License 2.0, see [LICENSE](LICENSE).
