package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.partner.application.PartnerSearchCriteria;
import io.infranexum.itam.partner.domain.*;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic JDBC coverage for the governed ITAM partner catalogue. */
final class JdbcPartnerRepositoryCoverageTest {
    private static final Instant T=Instant.parse("2026-08-16T12:00:00Z");
    private static final LocalDate D=LocalDate.of(2026,8,16);
    private static final DomainIdentifier PARTNER=id(10), ORG=id(11), ACTOR=id(12);

    @Test void countUniquenessIdentityAndLifecycleWritesAreCovered() {
        Partner partner=partner();
        var tx=connection(query(Map.of("count",1L)),query(Map.of("exists",1)),query(Map.of("exists",1)),
                update(1),update(1),update(1),update(1));
        var repo=new JdbcPartnerRepository(dataSource(tx.connection()),transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(1,repo.count());
        assertTrue(repo.existsByCode(ORG,new PartnerCode("VENDOR")));
        assertFalse(repo.hasIdentityTokenCollision(ORG,Set.of()));
        assertTrue(repo.hasIdentityTokenCollision(ORG,Set.of("name:FR:vendor sas")));
        repo.insert(partner);
        repo.updateLifecycle(partner,1);
        assertTrue(tx.sql().stream().anyMatch(sql->sql.contains("partner_identity_token")));
    }

    @Test void findHydratesChildrenAndMissingRowsAcrossConnectionModes() {
        var scripts=new java.util.ArrayList<JdbcScriptedSupport.Script>();
        scripts.add(query(coreRow(JdbcDatabaseDialect.POSTGRESQL)));
        scripts.add(query(Map.of("partner_id",PARTNER.value(),"role_code","MANUFACTURER")));
        scripts.add(query(List.of()));scripts.add(query(List.of()));scripts.add(query(List.of()));scripts.add(query(List.of()));
        var tx=connection(scripts.toArray(JdbcScriptedSupport.Script[]::new));
        var repo=new JdbcPartnerRepository(dataSource(tx.connection()),transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        Partner found=repo.findById(PARTNER).orElseThrow();
        assertEquals(Set.of(PartnerRole.MANUFACTURER),found.roles());
        assertEquals("Vendor SAS",found.legalName());

        var missing=connection(query(List.of()));
        assertTrue(new JdbcPartnerRepository(dataSource(missing.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).findById(PARTNER).isEmpty());
    }

    @Test void searchCoversEveryFilterCursorHydrationAndOracleLimit() {
        var scripts=new java.util.ArrayList<JdbcScriptedSupport.Script>();
        scripts.add(query(coreRow(JdbcDatabaseDialect.POSTGRESQL)));
        scripts.add(query(Map.of("partner_id",PARTNER.value(),"role_code","MANUFACTURER")));
        scripts.add(query(List.of()));scripts.add(query(List.of()));scripts.add(query(List.of()));scripts.add(query(List.of()));
        var pg=connection(scripts.toArray(JdbcScriptedSupport.Script[]::new));
        var criteria=new PartnerSearchCriteria(ORG,PartnerRole.MANUFACTURER,PartnerAuthorizationStatus.DRAFT,"fr","ISO",D,id(9),20);
        var page=new JdbcPartnerRepository(dataSource(pg.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).search(criteria);
        assertEquals(1,page.items().size());assertNull(page.nextCursor());
        assertTrue(pg.sql().getFirst().contains("LIMIT ?"));

        var or=connection(query(List.of()));
        var empty=new JdbcPartnerRepository(dataSource(or.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE)
                .search(new PartnerSearchCriteria(null,null,null,null,null,null,null,10));
        assertTrue(empty.items().isEmpty());assertTrue(or.sql().getFirst().contains("FETCH NEXT ? ROWS ONLY"));
    }

    @Test void writeAndQueryFailuresPreserveStableConflicts() {
        Partner partner=partner();
        var unique=connection(updateFailure(new SQLException("duplicate","23505")));
        assertEquals("PARTNER_DUPLICATE",assertThrows(PartnerConflictException.class,()->new JdbcPartnerRepository(dataSource(unique.connection()),transaction(unique.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(partner)).code());
        var version=connection(update(0));
        assertEquals("VERSION_CONFLICT",assertThrows(PartnerConflictException.class,()->new JdbcPartnerRepository(dataSource(version.connection()),transaction(version.connection()),JdbcDatabaseDialect.POSTGRESQL).updateLifecycle(partner,1)).code());
        var failed=connection(queryFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->new JdbcPartnerRepository(dataSource(failed.connection()),transaction(failed.connection()),JdbcDatabaseDialect.POSTGRESQL).count());
    }


    @Test void countPaginationNullableDatesAndAffectedRowGuardsAreCovered() {
        var noCount=connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcPartnerRepository(
                dataSource(noCount.connection()), transaction(noCount.connection()),
                JdbcDatabaseDialect.POSTGRESQL).count());

        Partner partner=Partner.restore(PARTNER,ORG,null,new PartnerCode("VENDOR"),"Vendor SAS","Vendor","FR",
                Set.of(PartnerRole.MANUFACTURER),PartnerAuthorizationStatus.DRAFT,D,null,null,null,List.of(),List.of(),
                List.of(),List.of(),1,T,T,ACTOR,ACTOR,"created");
        var zero=connection(update(0));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcPartnerRepository(dataSource(zero.connection()),
                transaction(zero.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(partner));
        var ok=connection(update(1),update(1));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcPartnerRepository(dataSource(ok.connection()),
                transaction(ok.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(partner));
        assertTrue(ok.parameters().getFirst().containsValue(null));

        var one=coreRow(JdbcDatabaseDialect.POSTGRESQL);
        var two=new LinkedHashMap<String,Object>(one); two.put("id",id(13).value());
        var scripts=new java.util.ArrayList<JdbcScriptedSupport.Script>();
        scripts.add(query(List.of(one,two)));
        scripts.add(query(Map.of("partner_id",PARTNER.value(),"role_code","MANUFACTURER")));
        scripts.add(query(List.of())); scripts.add(query(List.of())); scripts.add(query(List.of())); scripts.add(query(List.of()));
        var paged=connection(scripts.toArray(JdbcScriptedSupport.Script[]::new));
        var page=new JdbcPartnerRepository(dataSource(paged.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .search(new PartnerSearchCriteria(null,null,null,null,null,null,null,1));
        assertEquals(1,page.items().size()); assertEquals(PARTNER,page.nextCursor());
    }

    private static Partner partner(){return Partner.restore(PARTNER,ORG,null,new PartnerCode("VENDOR"),"Vendor SAS","Vendor","FR",Set.of(PartnerRole.MANUFACTURER),PartnerAuthorizationStatus.DRAFT,D,D.plusYears(1),null,null,List.of(),List.of(),List.of(),List.of(),1,T,T,ACTOR,ACTOR,"created");}
    private static Map<String,Object> coreRow(JdbcDatabaseDialect d){var r=new LinkedHashMap<String,Object>();r.put("id",jdbc(d,PARTNER));r.put("governing_organization_id",jdbc(d,ORG));r.put("governing_subdivision_id",null);r.put("code","VENDOR");r.put("legal_name","Vendor SAS");r.put("display_name","Vendor");r.put("country_code","FR");r.put("authorization_status","DRAFT");r.put("valid_from",Date.valueOf(D));r.put("valid_until",Date.valueOf(D.plusYears(1)));r.put("official_website",null);r.put("support_portal",null);r.put("version",1L);r.put("created_at",T);r.put("updated_at",T);r.put("created_by",jdbc(d,ACTOR));r.put("updated_by",jdbc(d,ACTOR));r.put("last_reason","created");return r;}
    private static Object jdbc(JdbcDatabaseDialect d,DomainIdentifier id){return d==JdbcDatabaseDialect.POSTGRESQL?id.value():id.toString();}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
