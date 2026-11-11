package io.github.itsmestefanjay.camunda.stackit.secrets;

import java.io.Serial;

/** Secrets could not be read; messages never contain secret values. */
public class StackitSecretsException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Without cause. */
  public StackitSecretsException(String message) {
    super(message);
  }

  /** With cause. */
  public StackitSecretsException(String message, Throwable cause) {
    super(message, cause);
  }
}
