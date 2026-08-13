package io.infranexum.rsot.domain;

import java.util.List;

/** Normative draft.21 initial authority matrix and RSOT context map. */
public final class InitialRsotGovernance {
    public static final String MATRIX_VERSION = "2.0.0-draft.21";

    private InitialRsotGovernance() {}

    public static List<AuthorityMatrixEntry> authorityMatrix() {
        return List.of(
                row(1, "Organisation, subdivision", "Organisation", "référence, scope, snapshot d’affichage", "l’autorité Organisation prévaut"),
                row(2, "Identité utilisateur et groupes", "IAM", "référence d’acteur, audit, ownership", "l’autorité IAM prévaut"),
                row(3, "Identité canonique d’un actif", "RSOT", "création, fusion, séparation, alias", "workflow RSOT"),
                row(4, "Observation brute", "Discovery", "association et provenance", "observation immuable, pas d’écrasement"),
                row(5, "Localisation physique", "DCIM", "consolidation sur l’actif", "conflit remonté à DCIM/RSOT"),
                row(6, "Adresse IP, préfixe, DNS, DHCP", "DDI", "relation canonique et recherche", "DDI prévaut"),
                row(7, "Contrat, garantie, licence patrimoniale", "ITAM", "référence et statut consolidé", "ITAM prévaut"),
                row(8, "Profil d’installation, quota, capability", "Core Capabilities", "lecture pour décisions", "Core prévaut"),
                row(9, "Politique de qualité", "Governance/RSOT", "exécution et preuve", "version active approuvée"));
    }

    public static List<ContextRelationship> contextMap() {
        return List.of(
                context(1, "Organization", "scope et identifiants d’organisation"),
                context(2, "IAM", "acteurs et décisions d’accès"),
                context(3, "Discovery", "observations et preuves immuables"),
                context(4, "DDI", "changements dont DDI est autorité"),
                context(5, "DCIM", "changements dont DCIM est autorité"),
                context(6, "ITAM", "changements dont ITAM est autorité"),
                context(7, "Governance", "approbation des politiques selon le workflow défini"),
                context(8, "Core Audit", "réception des événements d’audit"),
                context(9, "Core Contracts/Compatibility", "registre de schémas"),
                context(10, "Core Capabilities", "capabilities et quotas"));
    }

    private static AuthorityMatrixEntry row(int position, String information, String authority, String contribution, String conflict) {
        return new AuthorityMatrixEntry(position, information, authority, contribution, conflict, MATRIX_VERSION);
    }

    private static ContextRelationship context(int position, String provider, String contribution) {
        return new ContextRelationship(position, provider, contribution, false);
    }
}
