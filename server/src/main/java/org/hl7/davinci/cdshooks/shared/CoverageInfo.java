package org.hl7.davinci.cdshooks.shared;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;

/**
 * Coverage information to populate the CRD Coverage Information Extension.
 *
 * @see <a href="https://build.fhir.org/ig/HL7/davinci-crd/en/StructureDefinition-ext-coverage-information.html">CRD Coverage Information Extension</a>
 */
public class CoverageInfo {

  /**
   * Reference to the applicable Coverage resource (1..1, required)
   */
  private Reference coverage;

  /**
   * Whether the service is covered (1..1, required): 'covered' | 'not-covered' | 'conditional'
   */
  private String covered;

  /**
   * Whether prior authorization is needed (0..1): 'no-auth' | 'auth-needed' | 'satisfied' | 'performpa' | 'conditional'
   */
  private String paNeeded;

  /**
   * Whether additional documentation is needed (0..*): 'clinical' | 'admin' | 'patient' | 'conditional'
   */
  private List<String> docNeeded = new ArrayList<>();

  /**
   * Reason for additional documentation (0..*): 'withpa' | 'withclaim' | 'withorder' | 'retain-doc' | 'OTH'
   */
  private List<String> docPurpose = new ArrayList<>();

  /**
   * Whether performer, location, or date info is needed (0..*): 'performer' | 'location' | 'timeframe' | 'contract-window' | 'detail-code' | 'OTH'
   */
  private List<String> infoNeeded = new ArrayList<>();

  /**
   * Billing codes required for the claim (0..*)
   */
  private List<Coding> billingCodes = new ArrayList<>();

  /**
   * Reason codes explaining coverage decisions (0..*)
   */
  private List<CodeableConcept> reasonCodes = new ArrayList<>();

  /**
   * Additional coverage detail information (0..*)
   * Each detail has: category (code), code (CodeableConcept), value (simple type), qualification (string)
   */
  private List<CoverageDetail> details = new ArrayList<>();

  /**
   * Questionnaire URLs for DTR launch (0..*)
   */
  private List<String> questionnaireUrls = new ArrayList<>();

  /**
   * Date of this coverage assertion (1..1, required)
   */
  private LocalDate date;

  /**
   * Unique identifier for this coverage assertion (1..1, required)
   */
  private String assertionId;

  /**
   * Prior authorization ID when pa-needed = 'satisfied' (0..1)
   */
  private String satisfiedPaId;

  /**
   * Expiration date for this coverage assertion (0..1)
   */
  private LocalDate expiryDate;

  /**
   * References to dependent orders (0..*)
   */
  private List<Reference> dependencies = new ArrayList<>();

  /**
   * Contact information for payer support (0..*)
   */
  private List<ContactPoint> contacts = new ArrayList<>();

  /**
   * The base extension produced by CQL (optional)
   */
  private Extension baseExtension;

  public Reference getCoverage() {
    return coverage;
  }

  public void setCoverage(Reference coverage) {
    this.coverage = coverage;
  }

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

  public List<String> getDocNeeded() {
    return docNeeded;
  }

  public void setDocNeeded(List<String> docNeeded) {
    this.docNeeded = docNeeded;
  }

  public void addDocNeeded(String doc) {
    this.docNeeded.add(doc);
  }

  public List<String> getDocPurpose() {
    return docPurpose;
  }

  public void setDocPurpose(List<String> docPurpose) {
    this.docPurpose = docPurpose;
  }

  public void addDocPurpose(String purpose) {
    this.docPurpose.add(purpose);
  }

  public List<String> getInfoNeeded() {
    return infoNeeded;
  }

  public void setInfoNeeded(List<String> infoNeeded) {
    this.infoNeeded = infoNeeded;
  }

  public void addInfoNeeded(String info) {
    this.infoNeeded.add(info);
  }

  public List<Coding> getBillingCodes() {
    return billingCodes;
  }

  public void setBillingCodes(List<Coding> billingCodes) {
    this.billingCodes = billingCodes;
  }

  public void addBillingCode(Coding code) {
    this.billingCodes.add(code);
  }

  public List<CodeableConcept> getReasonCodes() {
    return reasonCodes;
  }

  public void setReasonCodes(List<CodeableConcept> reasonCodes) {
    this.reasonCodes = reasonCodes;
  }

  public void addReasonCode(CodeableConcept reason) {
    this.reasonCodes.add(reason);
  }

  public List<CoverageDetail> getDetails() {
    return details;
  }

  public void setDetails(List<CoverageDetail> details) {
    this.details = details;
  }

  public void addDetail(CoverageDetail detail) {
    this.details.add(detail);
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

  public LocalDate getDate() {
    return date;
  }

  public void setDate(LocalDate date) {
    this.date = date;
  }

  public String getAssertionId() {
    return assertionId;
  }

  public void setAssertionId(String assertionId) {
    this.assertionId = assertionId;
  }

  public String getSatisfiedPaId() {
    return satisfiedPaId;
  }

  public void setSatisfiedPaId(String satisfiedPaId) {
    this.satisfiedPaId = satisfiedPaId;
  }

  public LocalDate getExpiryDate() {
    return expiryDate;
  }

  public void setExpiryDate(LocalDate expiryDate) {
    this.expiryDate = expiryDate;
  }

  public List<Reference> getDependencies() {
    return dependencies;
  }

  public void setDependencies(List<Reference> dependencies) {
    this.dependencies = dependencies;
  }

  public void addDependency(Reference dependency) {
    this.dependencies.add(dependency);
  }

  public List<ContactPoint> getContacts() {
    return contacts;
  }

  public void setContacts(List<ContactPoint> contacts) {
    this.contacts = contacts;
  }

  public void addContact(ContactPoint contact) {
    this.contacts.add(contact);
  }

  public Extension getBaseExtension() {
    return baseExtension;
  }

  public void setBaseExtension(Extension baseExtension) {
    this.baseExtension = baseExtension;
  }

  /**
   * Represents a detail element within the coverage-information extension.
   * Contains category, code, value, and qualification sub-elements.
   */
  public static class CoverageDetail {
    /**
     * Category of the detail: 'allowed-quantity' | 'allowed-period' | 'in-network' | 'contracted' | 'OTH'
     */
    private String category;

    /**
     * Code further specifying the detail
     */
    private CodeableConcept code;

    /**
     * Value for the detail (can be Quantity, Money, boolean, string, etc.)
     */
    private Object value;

    /**
     * Qualification text providing additional context
     */
    private String qualification;

    public String getCategory() {
      return category;
    }

    public void setCategory(String category) {
      this.category = category;
    }

    public CodeableConcept getCode() {
      return code;
    }

    public void setCode(CodeableConcept code) {
      this.code = code;
    }

    public Object getValue() {
      return value;
    }

    public void setValue(Object value) {
      this.value = value;
    }

    public String getQualification() {
      return qualification;
    }

    public void setQualification(String qualification) {
      this.qualification = qualification;
    }
  }
}
