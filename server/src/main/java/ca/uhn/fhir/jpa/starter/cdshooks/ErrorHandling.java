package ca.uhn.fhir.jpa.starter.cdshooks;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.davinci.cdshooks.error.CdsHooksException;
import org.hl7.davinci.cdshooks.error.OperationOutcomeBuilder;
import org.hl7.fhir.r4.model.OperationOutcome;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

public class ErrorHandling {

	private static final FhirContext fhirContext = FhirContext.forR4();

	private ErrorHandling() {}

	public static void handleError(
			HttpServletResponse response, String message, Exception e, AppProperties myAppProperties)
			throws IOException {
		setAccessControlHeaders(response, myAppProperties);

		// Check if this is a CDS Hooks specific exception with OperationOutcome
		if (e instanceof CdsHooksException) {
			handleCdsHooksException((CdsHooksException) e, response);
			return;
		}

		// Check if the cause is a CDS Hooks exception
		if (e.getCause() instanceof CdsHooksException) {
			handleCdsHooksException((CdsHooksException) e.getCause(), response);
			return;
		}

		// Check if this is a BaseServerResponseException (HAPI exceptions)
		// If so, handle the exception and return
		if (e instanceof BaseServerResponseException) {
			handleServerResponseException((BaseServerResponseException) e, response);
			return;
		} else if (e.getCause() instanceof BaseServerResponseException) {
			handleServerResponseException((BaseServerResponseException) e.getCause(), response);
			return;
		}

		// Legacy error handling for backward compatibility (non-FHIR exceptions)
		response.setStatus(500);
		response.getWriter().println(message);
		printMessageAndCause(e, response);
		printStackTrack(e, response);
	}

	/**
	 * Handles CDS Hooks exceptions by returning proper HTTP status codes
	 * and OperationOutcome resources as JSON.
	 */
	private static void handleCdsHooksException(CdsHooksException e, HttpServletResponse response)
			throws IOException {
		response.setStatus(e.getStatusCode());
		response.setContentType("application/fhir+json;charset=UTF-8");

		// Build OperationOutcome based on exception type
		OperationOutcome outcome;
		if (e instanceof CdsHooksException.PreconditionFailedException) {
			outcome = OperationOutcomeBuilder.createPreconditionFailedOutcome(e.getMessage());
		} else if (e instanceof CdsHooksException.BadRequestException) {
			outcome = OperationOutcomeBuilder.createBadRequestOutcome(e.getMessage());
		} else if (e instanceof CdsHooksException.UnprocessableEntityException) {
			outcome = OperationOutcomeBuilder.createUnprocessableEntityOutcome(e.getMessage());
		} else {
			outcome = OperationOutcomeBuilder.createOperationOutcome(
				OperationOutcome.IssueSeverity.ERROR,
				OperationOutcome.IssueType.EXCEPTION,
				"CDS Hooks Error",
				e.getMessage()
			);
		}

		// Serialize OperationOutcome to JSON
		String outcomeJson = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(outcome);
		response.getWriter().println(outcomeJson);
	}

	private static void handleServerResponseException(BaseServerResponseException e, HttpServletResponse response)
			throws IOException {
		// Convert BaseServerResponseException to CDS Hooks errors with OperationOutcome
		response.setContentType("application/fhir+json;charset=UTF-8");

		switch (e.getStatusCode()) {
			case 401:
			case 403:
				String authMessage = "Precondition Failed. Remote FHIR server returned: " + e.getStatusCode() + ". " +
					"Ensure that the fhirAuthorization token is set or that the remote server allows unauthenticated access.";
				response.setStatus(412);
				OperationOutcome authOutcome = OperationOutcomeBuilder.createPreconditionFailedOutcome(authMessage);
				response.getWriter().println(fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(authOutcome));
				break;
			case 404:
				String notFoundMessage = "Precondition Failed. Remote FHIR server returned: " + e.getStatusCode() + ". " +
					"Ensure the resource exists on the remote server.";
				response.setStatus(412);
				OperationOutcome notFoundOutcome = OperationOutcomeBuilder.createPreconditionFailedOutcome(notFoundMessage);
				response.getWriter().println(fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(notFoundOutcome));
				break;
			case 412:
				// Prefetch failures - extract meaningful message
				String message = e.getMessage();
				if (message == null) {
					message = "Precondition Failed. Required prefetch data could not be retrieved.";
				}
				response.setStatus(412);
				OperationOutcome prefetchOutcome = OperationOutcomeBuilder.createPreconditionFailedOutcome(message);
				response.getWriter().println(fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(prefetchOutcome));
				break;
			default:
				// For any other status code, return it with OperationOutcome
				response.setStatus(e.getStatusCode());
				String defaultMessage = "Remote FHIR server error: " + e.getMessage();
				OperationOutcome defaultOutcome = OperationOutcomeBuilder.createOperationOutcome(
					OperationOutcome.IssueSeverity.ERROR,
					OperationOutcome.IssueType.EXCEPTION,
					"Remote FHIR Server Error",
					defaultMessage
				);
				response.getWriter().println(fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(defaultOutcome));
		}
	}

	private static void printMessageAndCause(Exception e, HttpServletResponse response) throws IOException {
		if (e.getMessage() != null) {
			response.getWriter().println(e.getMessage());
		}

		if (e.getCause() != null && e.getCause().getMessage() != null) {
			response.getWriter().println(e.getCause().getMessage());
		}
	}

	private static void printStackTrack(Exception e, HttpServletResponse response) throws IOException {
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		String exceptionAsString = sw.toString();
		response.getWriter().println(exceptionAsString);
	}

	public static void setAccessControlHeaders(HttpServletResponse resp, AppProperties myAppProperties) {
		if (myAppProperties.getCors() != null) {
			if (myAppProperties.getCors().getAllow_Credentials()) {
				resp.setHeader(
						"Access-Control-Allow-Origin",
						myAppProperties.getCors().getAllowed_origin().stream()
								.findFirst()
								.get());
				resp.setHeader(
						"Access-Control-Allow-Methods",
						String.join(", ", Arrays.asList("GET", "HEAD", "POST", "OPTIONS")));
				resp.setHeader(
						"Access-Control-Allow-Headers",
						String.join(
								", ",
								Arrays.asList(
										"x-fhir-starter",
										"Origin",
										"Accept",
										"X-Requested-With",
										"Content-Type",
										"Authorization",
										"Cache-Control")));
				resp.setHeader(
						"Access-Control-Expose-Headers",
						String.join(", ", Arrays.asList("Location", "Content-Location")));
				resp.setHeader("Access-Control-Max-Age", "86400");
			}
		}
	}

	public static class CdsHooksError extends RuntimeException {
		public CdsHooksError(String message) {
			super(message);
		}
	}
}
