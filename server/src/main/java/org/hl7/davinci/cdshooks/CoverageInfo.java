package org.hl7.davinci.cdshooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds coverage information values extracted from CQL/RequestGroup for CRD responses.
 * Used to build the coverage-information extension on order resources.
 * 
 * @see <a href="http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information">CRD Coverage Information Extension</a>
 */
public class CoverageInfo {
  
  /** 
   * Whether the service is covered: 'covered' | 'not-covered' | 'conditional' 
   */
  private String covered;
  
  /** 
   * Whether prior authorization is needed: 'auth-needed' | 'no-auth' | 'satisfied' | etc. 
   */
  private String paNeeded;
  
  /** 
   * Whether additional documentation is needed: 'clinical' | 'admin' | 'patient' | 'conditional' | 'no-doc' 
   */
  private String docNeeded;
  
  /** 
   * Questionnaire URLs for DTR launch (canonical references to Questionnaire resources) 
   */
  private List<String> questionnaireUrls = new ArrayList<>();
  
  /** 
   * Unique identifier for this coverage assertion 
   */
  private String assertionId;

  public String getCovered() {
    return covered;
  }

  public void setCovered(String covered) {
    this.covered = covered;
  }

  public String getPaNeeded() {
    return paNeeded;
  }

  public void setPaNeeded(String paNeeded) {
    this.paNeeded = paNeeded;
  }

  public String getDocNeeded() {
    return docNeeded;
  }

  public void setDocNeeded(String docNeeded) {
    this.docNeeded = docNeeded;
  }

  public List<String> getQuestionnaireUrls() {
    return questionnaireUrls;
  }

  public void setQuestionnaireUrls(List<String> questionnaireUrls) {
    this.questionnaireUrls = questionnaireUrls;
  }

  public void addQuestionnaireUrl(String url) {
    this.questionnaireUrls.add(url);
  }

  public String getAssertionId() {
    return assertionId;
  }

  public void setAssertionId(String assertionId) {
    this.assertionId = assertionId;
  }
}
