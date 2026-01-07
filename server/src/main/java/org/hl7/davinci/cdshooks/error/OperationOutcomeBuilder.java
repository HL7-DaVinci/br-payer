package org.hl7.davinci.cdshooks.error;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;

/**
 * Builder for FHIR OperationOutcome resources used in error responses.
 *
 * Uses standard FHIR IssueType codes from http://hl7.org/fhir/issue-type
 */
public class OperationOutcomeBuilder {

    private OperationOutcomeBuilder() {}

    /**
     * Creates an OperationOutcome for CDS Hooks errors.
     *
     * @param severity The issue severity (error, warning, information)
     * @param issueType The standard FHIR issue type code
     * @param detailText Optional detail text for the issue
     * @param diagnostics Human-readable diagnostic message
     * @return OperationOutcome resource
     */
    public static OperationOutcome createOperationOutcome(
            IssueSeverity severity,
            IssueType issueType,
            String detailText,
            String diagnostics) {
        OperationOutcome outcome = new OperationOutcome();

        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue();
        issue.setSeverity(severity);
        issue.setCode(issueType);

        if (detailText != null) {
            CodeableConcept details = new CodeableConcept();
            details.setText(detailText);
            issue.setDetails(details);
        }

        if (diagnostics != null) {
            issue.setDiagnostics(diagnostics);
        }

        return outcome;
    }

    /**
     * Creates a 412 Precondition Failed OperationOutcome.
     * Used when required prefetch data is missing or couldn't be retrieved.
     */
    public static OperationOutcome createPreconditionFailedOutcome(String diagnostics) {
        return createOperationOutcome(
            IssueSeverity.ERROR,
            IssueType.INCOMPLETE,
            "Precondition Failed",
            diagnostics
        );
    }

    /**
     * Creates a 400 Bad Request OperationOutcome.
     * Used for malformed requests or invalid input.
     */
    public static OperationOutcome createBadRequestOutcome(String diagnostics) {
        return createOperationOutcome(
            IssueSeverity.ERROR,
            IssueType.INVALID,
            null,
            diagnostics
        );
    }

    /**
     * Creates a 422 Unprocessable Entity OperationOutcome.
     * Used when a request is well-formed but violates business rules.
     */
    public static OperationOutcome createUnprocessableEntityOutcome(String diagnostics) {
        return createOperationOutcome(
            IssueSeverity.ERROR,
            IssueType.BUSINESSRULE,
            null,
            diagnostics
        );
    }

    /**
     * Creates OperationOutcome for prefetch failure (patient not found, etc.)
     */
    public static OperationOutcome createPrefetchFailedOutcome(String resourceType, String prefetchKey, Exception cause) {
        String diagnostics = String.format(
            "Required prefetch resource '%s' (%s) could not be retrieved: %s",
            prefetchKey,
            resourceType,
            cause != null ? cause.getMessage() : "Unknown error"
        );
        return createPreconditionFailedOutcome(diagnostics);
    }

    /**
     * Creates OperationOutcome for invalid payer identifier
     */
    public static OperationOutcome createInvalidPayorOutcome() {
        return createBadRequestOutcome(
            "Coverage resource lacks valid payer identifier. Ensure Coverage.payor references an Organization with a valid identifier."
        );
    }
}
