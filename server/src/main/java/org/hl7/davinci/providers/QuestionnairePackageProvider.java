package org.hl7.davinci.providers;


import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.NotImplementedOperationException;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import org.hl7.davinci.common.BaseProvider;

@Component
public class QuestionnairePackageProvider extends BaseProvider {
  @Operation(
    name = "$questionnaire-package",
    type = Questionnaire.class,
    canonicalUrl = "http://hl7.org/fhir/us/davinci-dtr/OperationDefinition/questionnaire-package"
  )
  public Bundle questionnairePackage(
    @OperationParam(name = "coverage", min = 0, type = Coverage.class) Coverage theCoverage,
    @OperationParam(name = "order", min = 0, type = IAnyResource.class) IAnyResource theOrder,
    @OperationParam(name = "referenced", min = 0, type = IAnyResource.class) IAnyResource theReferenced,
    @OperationParam(name = "questionnaire", min = 0, type = CanonicalType.class) CanonicalType theQuestionnaire,
    @OperationParam(name = "context", min = 0, max = 1, type = StringType.class) StringType theContext,
    @OperationParam(name = "changedsince", min = 0, max = 1, type = DateTimeType.class) DateTimeType theChangedsince
  ) {
    // TODO: Implement operation $questionnaire-package
    throw new NotImplementedOperationException("Operation $questionnaire-package is not implemented");
    
    // Bundle retVal = new Bundle();
    // return retVal;
    
  }

  
}