package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.compliance.domain.*;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic JDBC coverage for contractual ITAM records, alerts and revisions. */
final class JdbcComplianceRepositoryCoverageTest {
    private static final Instant T=Instant.parse("2026-08-16T12:00:00Z");
    private static final LocalDate START=LocalDate.of(2026,1,1),END=LocalDate.of(2026,12,31);
    private static final DomainIdentifier ID=id(1),ASSET=id(2),PARTNER=id(3),TYPE=id(4),ACTOR=id(5),ORG=id(6),SUB=id(7),AUTH=id(8),MFR=id(9);

    @Test void countReadsPagesDueQueriesWarrantyTypesAndRevisionsMapRows(){
        var c=connection(query(Map.of("count",2L)),query(warrantyRow()),query(List.of(warrantyRow())),query(List.of(warrantyRow())),
                query(licenseRow()),query(List.of(licenseRow())),query(List.of(licenseRow())),
                query(coverageRow()),query(List.of(coverageRow())),query(List.of(coverageRow())),query(List.of(coverageRow())),
                query(warrantyTypeRow()),query(List.of(warrantyTypeRow())),query(List.of(warrantyRow())),query(List.of(licenseRow())),query(List.of(coverageRow())),query(List.of(revisionRow())));
        var r=new JdbcComplianceRepository(dataSource(c.connection()),transaction(c.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(2,r.contractRecordCount());assertTrue(r.findWarranty(ID).isPresent());assertEquals(1,r.warrantiesForAsset(ASSET).size());assertEquals(1,r.warrantyPage(ASSET,null,10).size());
        assertTrue(r.findLicense(ID).isPresent());assertEquals(1,r.licensesForAsset(ASSET).size());assertEquals(1,r.licensePage(ASSET,ID,10).size());
        assertTrue(r.findSupportCoverage(ID).isPresent());assertEquals(1,r.supportCoveragesForAsset(ASSET).size());assertEquals(1,r.supportCoveragePage(ASSET,ID,10).size());assertEquals(1,r.supportCoveragesForAuthorization(AUTH).size());
        assertTrue(r.findWarrantyType(TYPE).isPresent());assertEquals(1,r.warrantyTypes(true).size());
        assertEquals(1,r.warrantiesDueBetween(START,END).size());assertEquals(1,r.licensesDueBetween(START,END).size());assertEquals(1,r.supportCoveragesDueBetween(START,END).size());assertEquals(1,r.revisions("warranty",ID,0,10).size());
    }

    @Test void insertsUpdatesAlertDedupAndVersionConflictCoverMutationPaths(){
        Warranty w=Warranty.draft(ID,ASSET,PARTNER,TYPE,"standard",START,END,END,"CERT","proof",EvidenceSource.MANUAL,ACTOR,"create",T);
        SoftwareLicenseContract l=SoftwareLicenseContract.draft(id(20),ASSET,PARTNER,"C-1","subscription","production",10,START,END,END,"proof",EvidenceSource.MANUAL,ACTOR,"create",T);
        SupportCoverage coverage=SupportCoverage.draft(id(21),ASSET,PARTNER,AUTH,null,"hardware","gold",START,END,MFR,"server",ORG,SUB,"proof",ACTOR,"create",T);
        WarrantyType wt=new WarrantyType(TYPE,"STD","Standard",true,T,ACTOR);
        var c=connection(update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1));
        var r=new JdbcComplianceRepository(dataSource(c.connection()),transaction(c.connection()),JdbcDatabaseDialect.POSTGRESQL);
        r.insertWarranty(w);r.updateWarranty(w,1);r.insertLicense(l);r.updateLicense(l,1);r.insertSupportCoverage(coverage);r.updateSupportCoverage(coverage,1);r.insertWarrantyType(wt);
        ComplianceAlert alert=new ComplianceAlert(ComplianceAlertKind.WARRANTY_END,ID,ASSET,END,30,30);
        assertTrue(r.reserveAlert(alert,START));
        assertTrue(c.sql().stream().anyMatch(sql->sql.contains("compliance_revision")));

        var dup=connection(updateFailure(new SQLException("dup","23505")));
        assertFalse(new JdbcComplianceRepository(dataSource(dup.connection()),transaction(dup.connection()),JdbcDatabaseDialect.POSTGRESQL).reserveAlert(alert,START));
        var version=connection(update(0));
        assertEquals("VERSION_CONFLICT",assertThrows(ComplianceConflictException.class,()->new JdbcComplianceRepository(dataSource(version.connection()),transaction(version.connection()),JdbcDatabaseDialect.POSTGRESQL).updateSupportCoverage(coverage,1)).code());
    }

    @Test void validationOracleBooleanAndSqlFailuresCoverFailClosedBranches(){
        assertThrows(IllegalArgumentException.class,()->new JdbcComplianceRepository(dataSource(connection().connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).revisions("other",ID,0,10));
        assertThrows(IllegalArgumentException.class,()->new JdbcComplianceRepository(dataSource(connection().connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).revisions("warranty",ID,-1,10));
        var o=connection(query(List.of(warrantyTypeOracleRow())));
        var oracle=new JdbcComplianceRepository(dataSource(o.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE);
        assertTrue(oracle.warrantyTypes(true).getFirst().active());assertTrue(o.sql().getFirst().contains("active=1"));
        var failed=connection(queryFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(failed.connection()),transaction(failed.connection()),JdbcDatabaseDialect.POSTGRESQL).contractRecordCount());
    }


    @Test void boundaryConnectionsNullableContractsAndAffectedRowsAreCovered(){
        var noCount=connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(noCount.connection()),
                transaction(noCount.connection()),JdbcDatabaseDialect.POSTGRESQL).contractRecordCount());

        Warranty w=Warranty.draft(id(30),ASSET,PARTNER,TYPE,"standard",START,END,END,null,"proof",
                EvidenceSource.MANUAL,ACTOR,"create",T);
        var insertZero=connection(update(0));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(insertZero.connection()),
                transaction(insertZero.connection()),JdbcDatabaseDialect.POSTGRESQL).insertWarranty(w));
        var revisionZero=connection(update(1),update(0));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(revisionZero.connection()),
                transaction(revisionZero.connection()),JdbcDatabaseDialect.POSTGRESQL).insertWarranty(w));

        SoftwareLicenseContract open=SoftwareLicenseContract.draft(id(31),ASSET,PARTNER,"OPEN-1","subscription",
                "production",1,START,null,END,"proof",EvidenceSource.MANUAL,ACTOR,"create",T);
        var openWrite=connection(update(1),update(1));
        new JdbcComplianceRepository(dataSource(openWrite.connection()),transaction(openWrite.connection()),
                JdbcDatabaseDialect.POSTGRESQL).insertLicense(open);

        var oracleRevision=connection(update(1),update(1));
        Warranty oracleWarranty=Warranty.draft(id(32),ASSET,PARTNER,TYPE,"standard",START,END,END,"CERT","proof",
                EvidenceSource.MANUAL,ACTOR,"create",T);
        new JdbcComplianceRepository(dataSource(oracleRevision.connection()),transaction(oracleRevision.connection()),
                JdbcDatabaseDialect.ORACLE).insertWarranty(oracleWarranty);
        var oracleType=connection(update(1));
        new JdbcComplianceRepository(dataSource(oracleType.connection()),transaction(oracleType.connection()),
                JdbcDatabaseDialect.ORACLE).insertWarrantyType(new WarrantyType(id(33),"OR","Oracle",true,T,ACTOR));

        ComplianceAlert alert=new ComplianceAlert(ComplianceAlertKind.WARRANTY_END,ID,ASSET,END,30,30);
        var reservationMiss=connection(update(0));
        assertFalse(new JdbcComplianceRepository(dataSource(reservationMiss.connection()),transaction(reservationMiss.connection()),
                JdbcDatabaseDialect.POSTGRESQL).reserveAlert(alert,START));
        var reservationFailure=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(reservationFailure.connection()),
                transaction(reservationFailure.connection()),JdbcDatabaseDialect.POSTGRESQL).reserveAlert(alert,START));

        var detachedFind=connection(query(List.of()));
        assertTrue(new JdbcComplianceRepository(dataSource(detachedFind.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .findWarranty(ID).isEmpty());
        var detachedMany=connection(query(List.of()));
        assertTrue(new JdbcComplianceRepository(dataSource(detachedMany.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .warrantiesForAsset(ASSET).isEmpty());

        var detachedAuthorization=connection(query(List.of()));
        assertTrue(new JdbcComplianceRepository(dataSource(detachedAuthorization.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .findSupportAuthorization(AUTH).isEmpty());
        var sharedAuthorization=connection(query(List.of()));
        assertTrue(new JdbcComplianceRepository(dataSource(sharedAuthorization.connection()),transaction(sharedAuthorization.connection()),
                JdbcDatabaseDialect.POSTGRESQL).findSupportAuthorization(AUTH).isEmpty());

        var revisionRepo=new JdbcComplianceRepository(dataSource(connection().connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL);
        assertThrows(IllegalArgumentException.class,()->revisionRepo.revisions("warranty",ID,0,0));
        assertThrows(IllegalArgumentException.class,()->revisionRepo.revisions("warranty",ID,0,201));

        var typeZero=connection(update(0));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(typeZero.connection()),
                transaction(typeZero.connection()),JdbcDatabaseDialect.POSTGRESQL)
                .insertWarrantyType(new WarrantyType(id(34),"ZERO","Zero",true,T,ACTOR)));
    }

    @Test void sharedReadHelpersAndDueQueriesTranslateSqlFailuresDeterministically(){
        SQLException offline=new SQLException("offline","08006");
        var find=connection(queryFailure(offline));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(find.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).findWarranty(ID));

        var many=connection(queryFailure(offline));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(many.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).warrantiesForAsset(ASSET));

        var page=connection(queryFailure(offline));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(page.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).warrantyPage(ASSET,ID,10));

        var dueFour=connection(queryFailure(offline));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(dueFour.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).warrantiesDueBetween(START,END));

        var dueCoverage=connection(queryFailure(offline));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(dueCoverage.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).supportCoveragesDueBetween(START,END));

        var types=connection(queryFailure(offline));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(types.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).warrantyTypes(false));

        var revisions=connection(queryFailure(offline));
        assertThrows(JdbcPersistenceException.class,()->new JdbcComplianceRepository(dataSource(revisions.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).revisions("warranty",ID,0,10));
    }

    private static LinkedHashMap<String,Object> common(DomainIdentifier id){var r=new LinkedHashMap<String,Object>();r.put("id",id.value());r.put("asset_id",ASSET.value());r.put("proof_reference","proof");r.put("status","DRAFT");r.put("verified_at",null);r.put("verified_by",null);r.put("version",1L);r.put("created_at",T);r.put("updated_at",T);r.put("created_by",ACTOR.value());r.put("updated_by",ACTOR.value());r.put("last_reason","create");return r;}
    private static Map<String,Object> warrantyRow(){var r=common(ID);r.put("manufacturer_partner_id",PARTNER.value());r.put("warranty_type_id",TYPE.value());r.put("coverage_level","standard");r.put("warranty_start_date",Date.valueOf(START));r.put("warranty_end_date",Date.valueOf(END));r.put("manufacturer_support_end_date",Date.valueOf(END));r.put("contract_certificate_number","CERT");r.put("source","MANUAL");return r;}
    private static Map<String,Object> licenseRow(){var r=common(ID);r.put("publisher_partner_id",PARTNER.value());r.put("contract_number","C-1");r.put("license_model","subscription");r.put("usage_rights","production");r.put("entitlement_quantity",10L);r.put("starts_on",Date.valueOf(START));r.put("ends_on",Date.valueOf(END));r.put("publisher_support_end_date",Date.valueOf(END));r.put("source","MANUAL");return r;}
    private static Map<String,Object> coverageRow(){var r=common(ID);r.remove("verified_at");r.remove("verified_by");r.put("provider_partner_id",PARTNER.value());r.put("authorization_id",AUTH.value());r.put("contract_reference",null);r.put("coverage_type","hardware");r.put("service_level","gold");r.put("starts_on",Date.valueOf(START));r.put("ends_on",Date.valueOf(END));r.put("supported_manufacturer_id",MFR.value());r.put("supported_object_type","server");r.put("organization_id",ORG.value());r.put("subdivision_id",SUB.value());return r;}
    private static Map<String,Object> warrantyTypeRow(){return Map.of("id",TYPE.value(),"code","STD","display_name","Standard","active",true,"created_at",T,"created_by",ACTOR.value());}
    private static Map<String,Object> warrantyTypeOracleRow(){return Map.of("id",TYPE.toString(),"code","STD","display_name","Standard","active",1,"created_at",T,"created_by",ACTOR.toString());}
    private static Map<String,Object> revisionRow(){return Map.of("record_type","warranty","record_id",ID.value(),"version",1L,"status","DRAFT","proof_reference","proof","reason","create","snapshot_json","{}","recorded_at",T,"recorded_by",ACTOR.value());}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
