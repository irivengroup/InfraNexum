package io.infranexum.itam.compliance.domain;

/** Contractual deadline categories surfaced independently of record updated_at. */
public enum ComplianceAlertKind {
    WARRANTY_END("warranty_end","itam.warranty.expiring.v1"),
    MANUFACTURER_SUPPORT_END("manufacturer_support_end","itam.warranty.support_expiring.v1"),
    LICENSE_END("license_end","itam.license.expiring.v1"),
    SOFTWARE_SUPPORT_END("software_support_end","itam.license.support_expiring.v1"),
    THIRD_PARTY_SUPPORT_END("third_party_support_end","itam.support_coverage.expiring.v1");
    private final String wireValue,eventType;
    ComplianceAlertKind(String wireValue,String eventType){this.wireValue=wireValue;this.eventType=eventType;}
    public String wireValue(){return wireValue;} public String eventType(){return eventType;}
}
