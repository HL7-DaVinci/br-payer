package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.MedicationDispense;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;

/**
 * Container for all potential resources extracted from a CDS Hook request.
 */
public class HookResourceContext {

  private Patient patient;
  private Coverage coverage;
  private int coverageCount;
  private Encounter encounter;
  private List<Practitioner> practitioners = new ArrayList<>();
  private List<PractitionerRole> practitionerRoles = new ArrayList<>();
  private List<Organization> organizations = new ArrayList<>();
  private List<CareTeam> careTeams = new ArrayList<>();
  private List<Location> locations = new ArrayList<>();
  private List<Resource> orders = new ArrayList<>();
  private List<Appointment> appointments = new ArrayList<>();
  private List<MedicationStatement> medicationStatements = new ArrayList<>();
  private List<MedicationDispense> medicationDispenses = new ArrayList<>();
  private List<MedicationRequest> medicationHistory = new ArrayList<>();
  private List<Procedure> procedures = new ArrayList<>();
  private List<ServiceRequest> serviceRequests = new ArrayList<>();
  private List<Condition> conditions = new ArrayList<>();
  private Task task;
  private List<Task> tasks = new ArrayList<>();

  public Patient getPatient() {
    return patient;
  }

  public void setPatient(Patient patient) {
    this.patient = patient;
  }

  public Coverage getCoverage() {
    return coverage;
  }

  public void setCoverage(Coverage coverage) {
    this.coverage = coverage;
  }

  public int getCoverageCount() {
    return coverageCount;
  }

  public void setCoverageCount(int coverageCount) {
    this.coverageCount = coverageCount;
  }

  public Encounter getEncounter() {
    return encounter;
  }

  public void setEncounter(Encounter encounter) {
    this.encounter = encounter;
  }

  public List<Practitioner> getPractitioners() {
    return practitioners;
  }

  public void setPractitioners(List<Practitioner> practitioners) {
    this.practitioners = practitioners;
  }

  public void addPractitioner(Practitioner practitioner) {
    this.practitioners.add(practitioner);
  }

  public List<PractitionerRole> getPractitionerRoles() {
    return practitionerRoles;
  }

  public void setPractitionerRoles(List<PractitionerRole> practitionerRoles) {
    this.practitionerRoles = practitionerRoles;
  }

  public void addPractitionerRole(PractitionerRole practitionerRole) {
    this.practitionerRoles.add(practitionerRole);
  }

  public List<Organization> getOrganizations() {
    return organizations;
  }

  public void setOrganizations(List<Organization> organizations) {
    this.organizations = organizations;
  }

  public void addOrganization(Organization organization) {
    this.organizations.add(organization);
  }

  public List<CareTeam> getCareTeams() {
    return careTeams;
  }

  public void setCareTeams(List<CareTeam> careTeams) {
    this.careTeams = careTeams;
  }

  public void addCareTeam(CareTeam careTeam) {
    this.careTeams.add(careTeam);
  }

  public List<Location> getLocations() {
    return locations;
  }

  public void setLocations(List<Location> locations) {
    this.locations = locations;
  }

  public void addLocation(Location location) {
    this.locations.add(location);
  }

  public List<Resource> getOrders() {
    return orders;
  }

  public void setOrders(List<Resource> orders) {
    this.orders = orders;
  }

  public void addOrder(Resource order) {
    this.orders.add(order);
  }

  public List<Appointment> getAppointments() {
    return appointments;
  }

  public void setAppointments(List<Appointment> appointments) {
    this.appointments = appointments;
  }

  public void addAppointment(Appointment appointment) {
    this.appointments.add(appointment);
  }

  public Task getTask() {
    return task;
  }

  public void setTask(Task task) {
    this.task = task;
    if (task != null) {
      this.tasks.add(task);
    }
  }

  public List<Task> getTasks() {
    return tasks;
  }

  public void setTasks(List<Task> tasks) {
    this.tasks = tasks;
  }

  public void addTask(Task task) {
    this.tasks.add(task);
  }

  public List<MedicationStatement> getMedicationStatements() {
    return medicationStatements;
  }

  public void setMedicationStatements(List<MedicationStatement> medicationStatements) {
    this.medicationStatements = medicationStatements;
  }

  public void addMedicationStatement(MedicationStatement medicationStatement) {
    this.medicationStatements.add(medicationStatement);
  }

  public List<MedicationDispense> getMedicationDispenses() {
    return medicationDispenses;
  }

  public void setMedicationDispenses(List<MedicationDispense> medicationDispenses) {
    this.medicationDispenses = medicationDispenses;
  }

  public void addMedicationDispense(MedicationDispense medicationDispense) {
    this.medicationDispenses.add(medicationDispense);
  }

  public List<MedicationRequest> getMedicationHistory() {
    return medicationHistory;
  }

  public void setMedicationHistory(List<MedicationRequest> medicationHistory) {
    this.medicationHistory = medicationHistory;
  }

  public void addMedicationHistory(MedicationRequest medicationRequest) {
    this.medicationHistory.add(medicationRequest);
  }

  public List<Procedure> getProcedures() {
    return procedures;
  }

  public void setProcedures(List<Procedure> procedures) {
    this.procedures = procedures;
  }

  public void addProcedure(Procedure procedure) {
    this.procedures.add(procedure);
  }

  public List<ServiceRequest> getServiceRequests() {
    return serviceRequests;
  }

  public void setServiceRequests(List<ServiceRequest> serviceRequests) {
    this.serviceRequests = serviceRequests;
  }

  public void addServiceRequest(ServiceRequest serviceRequest) {
    this.serviceRequests.add(serviceRequest);
  }

  public List<Condition> getConditions() {
    return conditions;
  }

  public void setConditions(List<Condition> conditions) {
    this.conditions = conditions;
  }

  public void addCondition(Condition condition) {
    this.conditions.add(condition);
  }
}
