package org.hl7.davinci.cdshooks.error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CdsHooksExceptionTest {

  @Test
  void exceptionTypes_exposeExpectedHttpStatusesAndIssueCodes() {
    CdsHooksException.BadRequestException badRequest = new CdsHooksException.BadRequestException("bad");
    CdsHooksException.PreconditionFailedException precondition =
        new CdsHooksException.PreconditionFailedException("missing prefetch");
    CdsHooksException.UnprocessableEntityException unprocessable =
        new CdsHooksException.UnprocessableEntityException("business rule");

    assertEquals(400, badRequest.getStatusCode());
    assertEquals("invalid", badRequest.getIssueCode());

    assertEquals(412, precondition.getStatusCode());
    assertEquals("precondition-failed", precondition.getIssueCode());

    assertEquals(422, unprocessable.getStatusCode());
    assertEquals("business-rule", unprocessable.getIssueCode());
  }
}
