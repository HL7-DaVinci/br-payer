package org.hl7.davinci.cdshooks.error;

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;

/**
 * Base exception for CDS Hooks-related errors with specific HTTP status codes.
 * Extends BaseServerResponseException so it's caught by the existing servlet error handler.
 */
public class CdsHooksException extends BaseServerResponseException {
    private final String issueCode;

    public CdsHooksException(int statusCode, String message, String issueCode) {
        super(statusCode, message);
        this.issueCode = issueCode;
    }

    public CdsHooksException(int statusCode, String message, Throwable cause, String issueCode) {
        super(statusCode, message, cause);
        this.issueCode = issueCode;
    }

    public String getIssueCode() {
        return issueCode;
    }

    /**
     * 412 Precondition Failed - required prefetch data could not be retrieved
     */
    public static class PreconditionFailedException extends CdsHooksException {
        public PreconditionFailedException(String message) {
            super(412, message, "precondition-failed");
        }

        public PreconditionFailedException(String message, Throwable cause) {
            super(412, message, cause, "precondition-failed");
        }
    }

    /**
     * 400 Bad Request - invalid request format
     */
    public static class BadRequestException extends CdsHooksException {
        public BadRequestException(String message) {
            super(400, message, "invalid");
        }

        public BadRequestException(String message, Throwable cause) {
            super(400, message, cause, "invalid");
        }
    }

    /**
     * 422 Unprocessable Entity - valid format but semantically invalid
     */
    public static class UnprocessableEntityException extends CdsHooksException {
        public UnprocessableEntityException(String message) {
            super(422, message, "business-rule");
        }

        public UnprocessableEntityException(String message, Throwable cause) {
            super(422, message, cause, "business-rule");
        }
    }
}
