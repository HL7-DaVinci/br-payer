package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Task;

/**
 * Container for all potential resources extracted from a CDS Hook request.
 */
public class HookResourceContext {

  private Patient patient;
  private Coverage coverage;
  private Encounter encounter;
  private List<Practitioner> practitioners = new ArrayList<>();
  private List<PractitionerRole> practitionerRoles = new ArrayList<>();
  private List<Organization> organizations = new ArrayList<>();
  private List<Resource> orders = new ArrayList<>();
  private List<Appointment> appointments = new ArrayList<>();
  private Task task;

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
  }
}
