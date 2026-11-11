package io.github.itsmestefanjay.camunda.stackit.secrets;

import java.io.Serial;

/** Secrets could not be read from STACKIT Secrets Manager. Messages never contain secret values. */
public class StackitSecretsException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  public StackitSecretsException(String message) {
    super(message);
  }

  public StackitSecretsException(String message, Throwable cause) {
    super(message, cause);
  }
}
