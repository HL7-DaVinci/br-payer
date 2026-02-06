package org.hl7.davinci.cql;

import java.util.List;

import org.cqframework.cql.cql2elm.CqlCompilerException;

/**
 * Thrown when CQL-to-ELM compilation fails.
 */
public class ElmCompilationException extends RuntimeException {

  private final List<CqlCompilerException> errors;

  public ElmCompilationException(String message, List<CqlCompilerException> errors) {
    super(message);
    this.errors = errors;
  }

  public List<CqlCompilerException> getErrors() {
    return errors;
  }
}
