package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.application.FacilitySearchCriteria;
import io.infranexum.dcim.facility.domain.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic JDBC coverage for DCIM hierarchy persistence and dialect branches. */
final class JdbcFacilityRepositoryCoverageTest {
    private static final Instant T=Instant.parse("2026-08-16T12:00:00Z");
    private static final DomainIdentifier ID=id(1), ORG=id(2), SUB=id(3), ACTOR=id(4), AFTER=id(5);

    @Test void countsExistenceReadsAndActiveBuildingCountsUseTransactionAndDataSourcePaths() {
        var tx=connection(query(Map.of("count",2L)),query(Map.of("x",1)),query(Map.of("count",1L)),query(row(ID)));
        var repo=new JdbcFacilityRepository(dataSource(tx.connection()),transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(2,repo.count(FacilityKind.SITE));
        assertTrue(repo.existsByScopeCode(FacilityKind.SITE,SUB,new FacilityCode("PARIS")));
        assertEquals(1,repo.activeBuildingsForSite(ID));
        assertEquals("PARIS",repo.findById(ID).orElseThrow().code().value());

        var ds=connection(query(row(ID)),query(Map.of("count",3L)));
        var detached=new JdbcFacilityRepository(dataSource(ds.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL);
        assertTrue(detached.findById(ID).isPresent());
        assertEquals(3,detached.activeBuildingsForSite(ID));
    }

    @Test void insertUpdateSearchPaginationAndAllFiltersAreCovered() {
        FacilityNode node=site(ID);
        var writes=connection(update(1),update(1));
        var repo=new JdbcFacilityRepository(dataSource(writes.connection()),transaction(writes.connection()),JdbcDatabaseDialect.POSTGRESQL);
        repo.insert(node);
        repo.update(node,1);
        assertEquals(2,writes.sql().size());

        var search=connection(query(List.of(row(ID),row(AFTER))));
        var searchRepo=new JdbcFacilityRepository(dataSource(search.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL);
        var page=searchRepo.search(new FacilitySearchCriteria(ORG,SUB,FacilityKind.SITE,null,FacilityStatus.DRAFT,"FR",AFTER,1));
        assertEquals(1,page.items().size());
        assertEquals(ID,page.nextCursor());
        assertTrue(search.sql().getFirst().contains("organization_id=?"));
        assertTrue(search.sql().getFirst().contains("country_code=?"));
    }

    @Test void oracleSearchAndNullableColumnsExerciseAlternativeBindings() {
        var oracleRow=row(ID);
        oracleRow.put("id",ID.toString());oracleRow.put("organization_id",ORG.toString());oracleRow.put("subdivision_id",SUB.toString());oracleRow.put("scope_id",SUB.toString());oracleRow.put("created_by",ACTOR.toString());oracleRow.put("updated_by",ACTOR.toString());
        var c=connection(query(List.of(oracleRow)));
        var repo=new JdbcFacilityRepository(dataSource(c.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE);
        assertEquals(1,repo.search(new FacilitySearchCriteria(null,null,null,null,null,null,null,5)).items().size());
        assertTrue(c.sql().getFirst().contains("INFRANEXUM_DCIM_FACILITY"));
        assertTrue(c.sql().getFirst().contains("FETCH NEXT ? ROWS ONLY"));
    }

    @Test void uniqueVersionAndSqlFailuresRemainFailClosed() {
        var unique=connection(updateFailure(new SQLException("duplicate","23505")));
        var uniqueRepo=new JdbcFacilityRepository(dataSource(unique.connection()),transaction(unique.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals("DCIM_CODE_DUPLICATE",assertThrows(FacilityConflictException.class,()->uniqueRepo.insert(site(ID))).code());

        var version=connection(update(0));
        var versionRepo=new JdbcFacilityRepository(dataSource(version.connection()),transaction(version.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals("VERSION_CONFLICT",assertThrows(FacilityConflictException.class,()->versionRepo.update(site(ID),1)).code());

        var failed=connection(queryFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->new JdbcFacilityRepository(dataSource(failed.connection()),transaction(failed.connection()),JdbcDatabaseDialect.POSTGRESQL).count(FacilityKind.SITE));
        assertThrows(NullPointerException.class,()->new JdbcFacilityRepository(null,noTransaction(),JdbcDatabaseDialect.POSTGRESQL));
    }


    @Test void missingCountsParentFilterAndNullableIntegerAlternativesAreCovered() {
        var noCount=connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcFacilityRepository(dataSource(noCount.connection()),
                transaction(noCount.connection()),JdbcDatabaseDialect.POSTGRESQL).count(FacilityKind.SITE));
        var noChildren=connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcFacilityRepository(dataSource(noChildren.connection()),
                transaction(noChildren.connection()),JdbcDatabaseDialect.POSTGRESQL).activeBuildingsForSite(ID));

        var insertZero=connection(update(0));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcFacilityRepository(dataSource(insertZero.connection()),
                transaction(insertZero.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(site(ID)));

        FacilityNode building=FacilityNode.restore(id(6),FacilityKind.BUILDING,ORG,SUB,ID,SUB,new FacilityCode("BLD-1"),
                "Building",FacilityStatus.DRAFT,null,null,null,null,null,null,
                null,null,2,null,null,null,null,null,null,"building",1,T,T,ACTOR,ACTOR,"create");
        var buildingInsert=connection(update(1));
        new JdbcFacilityRepository(dataSource(buildingInsert.connection()),transaction(buildingInsert.connection()),
                JdbcDatabaseDialect.POSTGRESQL).insert(building);

        var search=connection(query(List.of(row(ID))));
        new JdbcFacilityRepository(dataSource(search.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .search(new FacilitySearchCriteria(null,null,null,ID,null,null,null,5));
        assertTrue(search.sql().getFirst().contains("parent_id=?"));
    }

    private static FacilityNode site(DomainIdentifier id){return FacilityNode.draft(id,FacilityKind.SITE,ORG,SUB,null,SUB,new FacilityCode("PARIS"),"Paris",
            "10 rue",null,"75001","Paris","FR","Europe/Paris",new BigDecimal("48.85"),new BigDecimal("2.35"),null,null,null,null,null,null,null,"primary",ACTOR,"create",T);}

    private static LinkedHashMap<String,Object> row(DomainIdentifier id){var r=new LinkedHashMap<String,Object>();
        r.put("id",id.value());r.put("facility_kind","SITE");r.put("organization_id",ORG.value());r.put("subdivision_id",SUB.value());r.put("parent_id",null);r.put("scope_id",SUB.value());
        r.put("code","PARIS");r.put("display_name","Paris");r.put("lifecycle_status","DRAFT");r.put("address_line_1","10 rue");r.put("address_line_2",null);r.put("postal_code","75001");r.put("city","Paris");r.put("country_code","FR");r.put("timezone","Europe/Paris");r.put("latitude",new BigDecimal("48.85"));r.put("longitude",new BigDecimal("2.35"));r.put("floor_count",null);r.put("level_number",null);r.put("area_m2",null);r.put("level_height_m",null);r.put("capacity_kw",null);r.put("access_restriction",null);r.put("zone_type",null);r.put("description","primary");r.put("version",1L);r.put("created_at",T);r.put("updated_at",T);r.put("created_by",ACTOR.value());r.put("updated_by",ACTOR.value());r.put("last_reason","create");return r;}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
