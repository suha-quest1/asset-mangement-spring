package com.vishworks.assetmgmt.service;

/**
 * An error returned by a job step (the Go {@code error} return value). A checked exception,
 * so the job failure message carries no "panic: " prefix.
 */
public class ServiceException extends Exception {
  public ServiceException(String message) {
    super(message);
  }

  public ServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
