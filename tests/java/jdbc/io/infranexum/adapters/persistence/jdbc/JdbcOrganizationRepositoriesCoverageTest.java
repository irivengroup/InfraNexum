package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.organization.domain.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic coverage of organization, subdivision and effective-scope JDBC contracts. */
final class JdbcOrganizationRepositoriesCoverageTest {
    private static final Instant T = Instant.parse("2026-08-16T12:00:00Z");
    private static final DomainIdentifier ORG = id(1), SUB = id(2), PARENT = id(3), SCOPE = id(4);

    @Test void organizationReadsWritesPaginationAndConflicts() {
        Map<String,Object> row = orgRow(JdbcDatabaseDialect.POSTGRESQL);
        var tx = connection(query(Map.of("count", 2L)), query(Map.of("exists",1)), query(row), query(row), update(1), update(1));
        var repo = new JdbcOrganizationRepository(dataSource(tx.connection()), transaction(tx.connection()), JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(2, repo.count()); assertTrue(repo.existsByCode(new OrganizationCode("ORG")));
        assertEquals(ORG, repo.findById(ORG).orElseThrow().id());
        assertEquals("ORG", repo.findByCode(new OrganizationCode("ORG")).orElseThrow().code().value());
        Organization org = Organization.restore(ORG,new OrganizationCode("ORG"),"Organization","Organization SAS","FR","fr","Europe/Paris","EUR",null,OrganizationState.ACTIVE,2,T,T.plusSeconds(1));
        repo.insert(org); repo.update(org,1);
        assertTrue(tx.sql().get(4).contains("INSERT INTO infranexum_org.organization"));

        var search = connection(query(List.of(row)));
        assertEquals(1,new JdbcOrganizationRepository(dataSource(search.connection()), noTransaction(), JdbcDatabaseDialect.POSTGRESQL)
                .search(" org ",OrganizationState.ACTIVE,5,20).size());
        assertTrue(search.sql().getFirst().contains("LIMIT ? OFFSET ?"));

        var oracle = connection(query(List.of(orgRow(JdbcDatabaseDialect.ORACLE))));
        assertEquals(1,new JdbcOrganizationRepository(dataSource(oracle.connection()), noTransaction(), JdbcDatabaseDialect.ORACLE)
                .search(null,null,7,30).size());
        assertTrue(oracle.sql().getFirst().contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"));

        var noRow = connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcOrganizationRepository(dataSource(noRow.connection()),transaction(noRow.connection()),JdbcDatabaseDialect.POSTGRESQL).count());
        var version = connection(update(0));
        assertEquals("VERSION_CONFLICT", assertThrows(OrganizationConflictException.class, () -> new JdbcOrganizationRepository(dataSource(version.connection()),transaction(version.connection()),JdbcDatabaseDialect.POSTGRESQL).update(org,1)).code());
        var unique = connection(updateFailure(new SQLException("duplicate","23505")));
        assertEquals("ORG_CODE_CONFLICT", assertThrows(OrganizationConflictException.class, () -> new JdbcOrganizationRepository(dataSource(unique.connection()),transaction(unique.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(org)).code());
    }


    @Test void organizationAlternateConnectionsBlankSearchAndAffectedRowGuardsAreCovered() {
        var row=orgRow(JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(ORG,new JdbcOrganizationRepository(dataSource(connection(query(row)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).findById(ORG).orElseThrow().id());
        assertEquals("ORG",new JdbcOrganizationRepository(dataSource(connection(query(row)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).findByCode(new OrganizationCode("ORG")).orElseThrow().code().value());
        assertTrue(new JdbcOrganizationRepository(dataSource(connection(query(List.of())).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).search("   ",null,0,10).isEmpty());

        Organization org = Organization.restore(ORG,new OrganizationCode("ORG"),"Organization","Organization SAS","FR","fr","Europe/Paris","EUR",PARENT,OrganizationState.ACTIVE,2,T,T.plusSeconds(1));
        var insertNonUnique=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->new JdbcOrganizationRepository(dataSource(insertNonUnique.connection()),transaction(insertNonUnique.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(org));
        var insertZero=connection(update(0));
        assertThrows(JdbcPersistenceException.class,()->new JdbcOrganizationRepository(dataSource(insertZero.connection()),transaction(insertZero.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(org));
    }

    @Test void subdivisionReadsWritesAndPagination() {
        Map<String,Object> row=subRow(JdbcDatabaseDialect.POSTGRESQL);
        var tx=connection(query(Map.of("count",1L)),query(Map.of("exists",1)),query(row),update(1),update(1));
        var repo=new JdbcSubdivisionRepository(dataSource(tx.connection()),transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(1,repo.countByOrganization(ORG)); assertTrue(repo.existsCode(ORG,new SubdivisionCode("SUB")));
        assertEquals(SUB,repo.findById(ORG,SUB).orElseThrow().id());
        Subdivision sub=Subdivision.restore(SUB,ORG,new SubdivisionCode("SUB"),"Subdivision","desc",SubdivisionType.DEPARTMENT,SubdivisionState.ACTIVE,null,2,T,T.plusSeconds(1),null);
        repo.insert(sub); repo.update(sub,1);
        var list=connection(query(List.of(row)));
        assertEquals(1,new JdbcSubdivisionRepository(dataSource(list.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).list(ORG,4,10).size());
        assertTrue(list.sql().getFirst().contains("LIMIT ? OFFSET ?"));
        var oracle=connection(query(List.of(subRow(JdbcDatabaseDialect.ORACLE))));
        assertEquals(1,new JdbcSubdivisionRepository(dataSource(oracle.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE).list(ORG,2,5).size());
        assertTrue(oracle.sql().getFirst().contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"));
        var conflict=connection(update(0));
        assertThrows(OrganizationConflictException.class,()->new JdbcSubdivisionRepository(dataSource(conflict.connection()),transaction(conflict.connection()),JdbcDatabaseDialect.POSTGRESQL).update(sub,1));
    }


    @Test void subdivisionMissingCountsAlternateConnectionAndInsertConflictsAreCovered() {
        var noCount=connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class,()->new JdbcSubdivisionRepository(dataSource(noCount.connection()),transaction(noCount.connection()),JdbcDatabaseDialect.POSTGRESQL).countByOrganization(ORG));
        var row=subRow(JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(SUB,new JdbcSubdivisionRepository(dataSource(connection(query(row)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).findById(ORG,SUB).orElseThrow().id());
        Subdivision sub=Subdivision.restore(SUB,ORG,new SubdivisionCode("SUB"),"Subdivision","desc",SubdivisionType.DEPARTMENT,SubdivisionState.ACTIVE,null,2,T,T.plusSeconds(1),null);
        var unique=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("SUBDIVISION_CODE_CONFLICT",assertThrows(OrganizationConflictException.class,()->new JdbcSubdivisionRepository(dataSource(unique.connection()),transaction(unique.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(sub)).code());
        var nonUnique=connection(updateFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->new JdbcSubdivisionRepository(dataSource(nonUnique.connection()),transaction(nonUnique.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(sub));
        var zero=connection(update(0));
        assertThrows(JdbcPersistenceException.class,()->new JdbcSubdivisionRepository(dataSource(zero.connection()),transaction(zero.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(sub));
    }

    @Test void temporalScopesRoundTripBothConnectionModes() {
        TemporalScope scope=new TemporalScope(SCOPE,ORG,SUB,ScopeType.OPERATIONAL,T,T.plusSeconds(3600),1,T);
        var tx=connection(update(1),query(scopeRow(JdbcDatabaseDialect.POSTGRESQL)));
        var repo=new JdbcTemporalScopeRepository(dataSource(tx.connection()),transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        repo.insert(scope); assertEquals(SCOPE,repo.findById(ORG,SCOPE).orElseThrow().id());
        var effective=connection(query(List.of(scopeRow(JdbcDatabaseDialect.POSTGRESQL))));
        assertEquals(1,new JdbcTemporalScopeRepository(dataSource(effective.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).effective(ORG,T.plusSeconds(1)).size());
        var missing=connection(query(List.of()));
        assertTrue(new JdbcTemporalScopeRepository(dataSource(missing.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).findById(ORG,SCOPE).isEmpty());
        var bad=connection(update(0));
        assertThrows(JdbcPersistenceException.class,()->new JdbcTemporalScopeRepository(dataSource(bad.connection()),transaction(bad.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(scope));
    }

    @Test void organizationIdempotencyCoversReadInsertAndUniqueConflict() {
        var row=new LinkedHashMap<String,Object>(); row.put("idempotency_key","k");row.put("payload_sha256","a".repeat(64));row.put("resource_type","organization");row.put("resource_id",ORG.value());row.put("created_at",T);
        var tx=connection(query(row),update(1));
        var repo=new JdbcOrganizationIdempotencyRepository(transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        var record=repo.find("k").orElseThrow(); assertEquals(ORG,record.resourceId()); repo.insert(record);
        var missing=connection(query(List.of())); assertTrue(new JdbcOrganizationIdempotencyRepository(transaction(missing.connection()),JdbcDatabaseDialect.POSTGRESQL).find("x").isEmpty());
        var unique=connection(updateFailure(new SQLException("dup","23505")));
        assertThrows(OrganizationConflictException.class,()->new JdbcOrganizationIdempotencyRepository(transaction(unique.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(record));
    }

    private static Map<String,Object> orgRow(JdbcDatabaseDialect d){var r=new LinkedHashMap<String,Object>();r.put("id",jdbc(d,ORG));r.put("code","ORG");r.put("display_name","Organization");r.put("legal_name","Organization SAS");r.put("country_code","FR");r.put("default_language","fr");r.put("timezone","Europe/Paris");r.put("currency","EUR");r.put("parent_organization_id",null);r.put("status","ACTIVE");r.put("version",2L);r.put("created_at",T);r.put("updated_at",T.plusSeconds(1));return r;}
    private static Map<String,Object> subRow(JdbcDatabaseDialect d){var r=new LinkedHashMap<String,Object>();r.put("id",jdbc(d,SUB));r.put("organization_id",jdbc(d,ORG));r.put("code","SUB");r.put("display_name","Subdivision");r.put("description_text","desc");r.put("type_name","DEPARTMENT");r.put("status","ACTIVE");r.put("parent_subdivision_id",null);r.put("version",2L);r.put("created_at",T);r.put("updated_at",T.plusSeconds(1));r.put("deleted_at",null);return r;}
    private static Map<String,Object> scopeRow(JdbcDatabaseDialect d){var r=new LinkedHashMap<String,Object>();r.put("id",jdbc(d,SCOPE));r.put("organization_id",jdbc(d,ORG));r.put("subdivision_id",jdbc(d,SUB));r.put("scope_type","OPERATIONAL");r.put("valid_from",T);r.put("valid_to",T.plusSeconds(3600));r.put("version",1L);r.put("created_at",T);return r;}
    private static Object jdbc(JdbcDatabaseDialect d,DomainIdentifier id){return d==JdbcDatabaseDialect.POSTGRESQL?id.value():id.toString();}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
