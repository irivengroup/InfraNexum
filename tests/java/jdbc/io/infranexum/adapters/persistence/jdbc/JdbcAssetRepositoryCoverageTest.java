package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.itam.asset.application.AssetSearchCriteria;
import io.infranexum.itam.asset.domain.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic JDBC coverage for ITAM assets and append-only custody history. */
final class JdbcAssetRepositoryCoverageTest {
    private static final Instant T=Instant.parse("2026-08-16T12:00:00Z");
    private static final LocalDate D=LocalDate.of(2026,8,16);
    private static final DomainIdentifier ASSET=id(20), RSOT=id(21), ORG=id(22), ACTOR=id(23), CORR=id(24), PRODUCER=id(25);

    @Test void countFindExistsAndSearchMapCompleteAssetRows() {
        var tx=connection(query(Map.of("count",1L)),query(Map.of("exists",1)),query(assetRow(JdbcDatabaseDialect.POSTGRESQL)));
        var repo=new JdbcAssetRepository(dataSource(tx.connection()),transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(1,repo.count()); assertTrue(repo.existsByRsotObjectId(RSOT));
        Asset found=repo.findById(ASSET).orElseThrow(); assertEquals(PRODUCER,found.producerPartnerId());

        var pg=connection(query(List.of(assetRow(JdbcDatabaseDialect.POSTGRESQL))));
        var page=new JdbcAssetRepository(dataSource(pg.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .search(new AssetSearchCriteria(ORG,AssetType.HARDWARE,AssetLifecycleStatus.ACQUIRED,RSOT,id(19),20));
        assertEquals(1,page.items().size());assertNull(page.nextAfterId());assertTrue(pg.sql().getFirst().contains("LIMIT ?"));
        var or=connection(query(List.of()));
        assertTrue(new JdbcAssetRepository(dataSource(or.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE)
                .search(new AssetSearchCriteria(null,null,null,null,null,10)).items().isEmpty());
        assertTrue(or.sql().getFirst().contains("FETCH NEXT ? ROWS ONLY"));
    }

    @Test void insertMetadataLifecycleAndCustodyWritesAreTransactional() {
        Asset asset=asset(); AssetCustodyEvent acquired=event(1,null,AssetLifecycleStatus.ACQUIRED,AssetCustodyEventType.ACQUIRED);
        var tx=connection(update(1),update(1),update(1),update(1),update(1));
        var repo=new JdbcAssetRepository(dataSource(tx.connection()),transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        repo.insert(asset,acquired); repo.updateMetadata(asset,1); repo.update(asset,1,event(2,AssetLifecycleStatus.ACQUIRED,AssetLifecycleStatus.ACQUIRED,AssetCustodyEventType.TRANSFERRED));
        assertTrue(tx.sql().stream().anyMatch(sql->sql.contains("asset_custody_event")));
    }

    @Test void custodyHistoryMapsNullAndNonNullPreviousStatus() {
        var row=custodyRow(JdbcDatabaseDialect.POSTGRESQL);var row2=new LinkedHashMap<>(row);row2.put("sequence_no",2L);row2.put("from_status","ACQUIRED");
        var pg=connection(query(List.of(row,row2)));
        var events=new JdbcAssetRepository(dataSource(pg.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).custodyHistory(ASSET,0,10);
        assertEquals(2,events.size());assertNull(events.getFirst().fromStatus());assertEquals(AssetLifecycleStatus.ACQUIRED,events.getLast().fromStatus());
    }

    @Test void uniquenessVersionAndSqlFailuresRemainExplicit() {
        Asset asset=asset();AssetCustodyEvent event=event(1,null,AssetLifecycleStatus.ACQUIRED,AssetCustodyEventType.ACQUIRED);
        var unique=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("ITAM_ASSET_RSOT_CONFLICT",assertThrows(AssetConflictException.class,()->new JdbcAssetRepository(dataSource(unique.connection()),transaction(unique.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(asset,event)).code());
        var v1=connection(update(0));assertEquals("VERSION_CONFLICT",assertThrows(AssetConflictException.class,()->new JdbcAssetRepository(dataSource(v1.connection()),transaction(v1.connection()),JdbcDatabaseDialect.POSTGRESQL).updateMetadata(asset,1)).code());
        var v2=connection(update(0));assertEquals("VERSION_CONFLICT",assertThrows(AssetConflictException.class,()->new JdbcAssetRepository(dataSource(v2.connection()),transaction(v2.connection()),JdbcDatabaseDialect.POSTGRESQL).update(asset,1,event)).code());
        var failed=connection(queryFailure(new SQLException("offline","08006")));assertThrows(JdbcPersistenceException.class,()->new JdbcAssetRepository(dataSource(failed.connection()),transaction(failed.connection()),JdbcDatabaseDialect.POSTGRESQL).count());
    }


    @Test void boundaryRowsPaginationAndAffectedRowGuardsAreCovered() {
        var noCount=connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcAssetRepository(
                dataSource(noCount.connection()), transaction(noCount.connection()),
                JdbcDatabaseDialect.POSTGRESQL).count());

        var detached = connection(query(assetRow(JdbcDatabaseDialect.POSTGRESQL)));
        assertEquals(ASSET, new JdbcAssetRepository(dataSource(detached.connection()), noTransaction(),
                JdbcDatabaseDialect.POSTGRESQL).findById(ASSET).orElseThrow().id());

        Asset asset=asset(); AssetCustodyEvent event=event(1,null,AssetLifecycleStatus.ACQUIRED,AssetCustodyEventType.ACQUIRED);
        var insertZero=connection(update(0));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcAssetRepository(dataSource(insertZero.connection()),
                transaction(insertZero.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(asset,event));
        var custodyZero=connection(update(1),update(0));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcAssetRepository(dataSource(custodyZero.connection()),
                transaction(custodyZero.connection()),JdbcDatabaseDialect.POSTGRESQL).insert(asset,event));

        var first=assetRow(JdbcDatabaseDialect.POSTGRESQL);
        var second=new LinkedHashMap<String,Object>(first); second.put("id",id(26).value());
        var paged=connection(query(List.of(first,second)));
        var page=new JdbcAssetRepository(dataSource(paged.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .search(new AssetSearchCriteria(null,null,null,null,null,1));
        assertEquals(1,page.items().size()); assertEquals(ASSET,page.nextAfterId());
    }

    private static Asset asset(){return Asset.restore(ASSET,RSOT,AssetType.HARDWARE,ORG,null,D,new AssetValue(new BigDecimal("123.45"),"EUR"),null,PRODUCER,AssetLifecycleStatus.ACQUIRED,AssetCustodian.organization(ORG),1,T,T,ACTOR,ACTOR,"created");}
    private static AssetCustodyEvent event(long seq,AssetLifecycleStatus from,AssetLifecycleStatus to,AssetCustodyEventType type){return new AssetCustodyEvent(id(30+(int)seq),ASSET,seq,type,from,to,AssetCustodian.organization(ORG),T.plusSeconds(seq),ACTOR,CORR,"reason",null);}
    private static Map<String,Object> assetRow(JdbcDatabaseDialect d){var r=new LinkedHashMap<String,Object>();r.put("id",jdbc(d,ASSET));r.put("rsot_object_id",jdbc(d,RSOT));r.put("asset_type","HARDWARE");r.put("owning_organization_id",jdbc(d,ORG));r.put("owning_subdivision_id",null);r.put("acquisition_date",Date.valueOf(D));r.put("acquisition_value",new BigDecimal("123.45"));r.put("currency_code","EUR");r.put("acquired_from_partner_id",null);r.put("producer_partner_id",jdbc(d,PRODUCER));r.put("lifecycle_status","ACQUIRED");r.put("current_custodian_kind","ORGANIZATION");r.put("current_custodian_id",jdbc(d,ORG));r.put("version",1L);r.put("created_at",T);r.put("updated_at",T);r.put("created_by",jdbc(d,ACTOR));r.put("updated_by",jdbc(d,ACTOR));r.put("last_reason","created");return r;}
    private static Map<String,Object> custodyRow(JdbcDatabaseDialect d){var r=new LinkedHashMap<String,Object>();r.put("event_id",jdbc(d,id(31)));r.put("asset_id",jdbc(d,ASSET));r.put("sequence_no",1L);r.put("event_type","ACQUIRED");r.put("from_status",null);r.put("to_status","ACQUIRED");r.put("custodian_kind","ORGANIZATION");r.put("custodian_id",jdbc(d,ORG));r.put("occurred_at",T);r.put("actor_id",jdbc(d,ACTOR));r.put("correlation_id",jdbc(d,CORR));r.put("reason","created");r.put("evidence_reference",null);return r;}
    private static Object jdbc(JdbcDatabaseDialect d,DomainIdentifier id){return d==JdbcDatabaseDialect.POSTGRESQL?id.value():id.toString();}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
