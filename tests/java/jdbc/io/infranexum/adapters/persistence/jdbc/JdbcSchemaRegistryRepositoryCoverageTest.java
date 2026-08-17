package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.compatibility.*;
import io.infranexum.core.contracts.ContractVersion;
import io.infranexum.core.contracts.DomainIdentifier;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic JDBC coverage for schema-registry lifecycle, profiles and dialect pagination. */
final class JdbcSchemaRegistryRepositoryCoverageTest {
    private static final Instant T=Instant.parse("2026-08-16T12:00:00Z");
    private static final DomainIdentifier SCHEMA=id(1), PROFILE=id(2);
    private static final String HASH="a".repeat(64);

    @Test void schemaReadsListsAndBothPaginationDialects() {
        var row=schemaRow(JdbcDatabaseDialect.POSTGRESQL,RegistryStatus.PUBLISHED);
        var pg=connection(query(row),query(row),query(row),query(List.of(row)));
        var repo=new JdbcSchemaRegistryRepository(dataSource(pg.connection()),transaction(pg.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(SCHEMA,repo.findSchema(SCHEMA).orElseThrow().id());
        assertEquals("rsot.router",repo.findSchemaVersion(" RSOT.ROUTER "," 1.0.0 ").orElseThrow().schemaKey());
        assertTrue(repo.latestPublishedSchema("rsot.router").isPresent());
        assertEquals(1,repo.listSchemas(" RSOT.ROUTER ",SchemaKind.RSOT_CANONICAL,RegistryStatus.PUBLISHED,3,20).size());
        assertTrue(pg.sql().get(3).contains("LIMIT ? OFFSET ?"));

        var or=connection(query(List.of(schemaRow(JdbcDatabaseDialect.ORACLE,RegistryStatus.PUBLISHED))));
        assertEquals(1,new JdbcSchemaRegistryRepository(dataSource(or.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE)
                .listSchemas(null,null,null,4,10).size());
        assertTrue(or.sql().getFirst().contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"));
        assertThrows(IllegalArgumentException.class,()->repo.findSchemaVersion(" ","1.0.0"));
    }

    @Test void schemaInsertUpdatePublishDeprecateAndConflicts() {
        RegisteredSchema draft=draftSchema();
        RegisteredSchema changed=draft.updateDraft("{\"v\":2}","b".repeat(64),T.plusSeconds(1));
        RegisteredSchema published=changed.publish(T.plusSeconds(2),CompatibilityReport.compatible(),"COMPATIBLE",null);
        RegisteredSchema deprecated=published.deprecate(T.plusSeconds(3),T.plusSeconds(3600),"retired");
        var tx=connection(update(1),update(1),update(1),update(1));
        var repo=new JdbcSchemaRegistryRepository(dataSource(tx.connection()),transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        repo.insertSchema(draft);repo.updateDraftSchema(changed);repo.publishSchema(published);repo.deprecateSchema(deprecated);
        assertTrue(tx.sql().getFirst().contains("CAST(? AS JSONB)"));

        var conflict=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("SCHEMA_VERSION_CONFLICT",assertThrows(SchemaRegistryException.class,()->new JdbcSchemaRegistryRepository(dataSource(conflict.connection()),transaction(conflict.connection()),JdbcDatabaseDialect.POSTGRESQL).insertSchema(draft)).code());
        var revision=connection(update(0));
        assertEquals("SCHEMA_REVISION_CONFLICT",assertThrows(SchemaRegistryException.class,()->new JdbcSchemaRegistryRepository(dataSource(revision.connection()),transaction(revision.connection()),JdbcDatabaseDialect.POSTGRESQL).publishSchema(published)).code());
    }

    @Test void profilesReadMembersInsertPublishDeprecateAndList() {
        var profileRow=profileRow(JdbcDatabaseDialect.POSTGRESQL,RegistryStatus.DRAFT);
        var memberRow=memberRow(JdbcDatabaseDialect.POSTGRESQL);
        var reads=connection(query(profileRow),query(memberRow),query(profileRow),query(memberRow),query(List.of(profileRow)),query(memberRow));
        var repo=new JdbcSchemaRegistryRepository(dataSource(reads.connection()),transaction(reads.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(1,repo.findProfile(PROFILE).orElseThrow().members().size());
        assertTrue(repo.findProfileVersion(" RSOT.PROFILE "," 1.0.0 ").isPresent());
        assertEquals(1,repo.listProfiles("rsot.profile",RegistryStatus.DRAFT,0,10).size());

        SchemaProfile draft=draftProfile(); SchemaProfile published=draft.publish(T.plusSeconds(2)); SchemaProfile deprecated=published.deprecate(T.plusSeconds(3),T.plusSeconds(4000),"retired");
        var tx=connection(update(1),batch(),update(1),update(1));
        var writes=new JdbcSchemaRegistryRepository(dataSource(tx.connection()),transaction(tx.connection()),JdbcDatabaseDialect.POSTGRESQL);
        writes.insertProfile(draft);writes.publishProfile(published);writes.deprecateProfile(deprecated);
        assertEquals(1,tx.batches().get(1).size());
    }

    @Test void profileOracleBooleanMappingAndFailuresAreCovered() {
        var profile=profileRow(JdbcDatabaseDialect.ORACLE,RegistryStatus.DRAFT);
        var member=memberRow(JdbcDatabaseDialect.ORACLE);member.put("required_member",1);
        var or=connection(query(List.of(profile)),query(member));
        assertTrue(new JdbcSchemaRegistryRepository(dataSource(or.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE)
                .listProfiles(null,null,2,5).getFirst().members().getFirst().required());
        assertTrue(or.sql().getFirst().contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"));

        var fail=connection(queryFailure(new SQLException("read failed","08006")));
        assertThrows(JdbcPersistenceException.class,()->new JdbcSchemaRegistryRepository(dataSource(fail.connection()),transaction(fail.connection()),JdbcDatabaseDialect.POSTGRESQL).findSchema(SCHEMA));
    }


    @Test void blankFiltersProfileConflictsOracleMembersAndAffectedRowsAreCovered() {
        var blankSchemas=connection(query(List.of()));
        assertTrue(new JdbcSchemaRegistryRepository(dataSource(blankSchemas.connection()),noTransaction(),
                JdbcDatabaseDialect.POSTGRESQL).listSchemas("   ",null,null,0,10).isEmpty());
        var blankProfiles=connection(query(List.of()));
        assertTrue(new JdbcSchemaRegistryRepository(dataSource(blankProfiles.connection()),noTransaction(),
                JdbcDatabaseDialect.POSTGRESQL).listProfiles("   ",null,0,10).isEmpty());

        RegisteredSchema draft=draftSchema();
        var zeroSchema=connection(update(0));
        assertThrows(JdbcPersistenceException.class,()->new JdbcSchemaRegistryRepository(dataSource(zeroSchema.connection()),
                transaction(zeroSchema.connection()),JdbcDatabaseDialect.POSTGRESQL).insertSchema(draft));

        SchemaProfile profile=draftProfile();
        var unique=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("SCHEMA_PROFILE_VERSION_CONFLICT",assertThrows(SchemaRegistryException.class,()->
                new JdbcSchemaRegistryRepository(dataSource(unique.connection()),transaction(unique.connection()),
                        JdbcDatabaseDialect.POSTGRESQL).insertProfile(profile)).code());
        var nonUnique=connection(update(0));
        assertThrows(JdbcPersistenceException.class,()->new JdbcSchemaRegistryRepository(dataSource(nonUnique.connection()),
                transaction(nonUnique.connection()),JdbcDatabaseDialect.POSTGRESQL).insertProfile(profile));

        var revision=connection(update(0));
        assertEquals("SCHEMA_REVISION_CONFLICT",assertThrows(SchemaRegistryException.class,()->
                new JdbcSchemaRegistryRepository(dataSource(revision.connection()),transaction(revision.connection()),
                        JdbcDatabaseDialect.POSTGRESQL).publishProfile(profile.publish(T.plusSeconds(2)))).code());

        var oracle=connection(update(1),batch());
        new JdbcSchemaRegistryRepository(dataSource(oracle.connection()),transaction(oracle.connection()),
                JdbcDatabaseDialect.ORACLE).insertProfile(profile);
        assertTrue(oracle.parameters().stream().anyMatch(parameters -> parameters.values().contains(1)));
    }

    private static RegisteredSchema draftSchema(){return new RegisteredSchema(SCHEMA,"rsot.router",SchemaKind.RSOT_CANONICAL,"team.rsot",ContractVersion.parse("1.0.0"),RegistryStatus.DRAFT,"{}",HASH,1,T,T,T,null,null,null,null,null,null);}
    private static SchemaProfile draftProfile(){return new SchemaProfile(PROFILE,"rsot.profile","team.rsot",ContractVersion.parse("1.0.0"),RegistryStatus.DRAFT,List.of(new SchemaProfileMember(1,SCHEMA,true)),HASH,1,T,T,null,null,null,null);}
    private static Map<String,Object> schemaRow(JdbcDatabaseDialect d,RegistryStatus status){var r=new LinkedHashMap<String,Object>();r.put("id",jdbc(d,SCHEMA));r.put("schema_key","rsot.router");r.put("schema_kind","RSOT_CANONICAL");r.put("owner_code","team.rsot");r.put("schema_version","1.0.0");r.put("status",status.name());r.put("definition_json","{}");r.put("checksum_sha256",HASH);r.put("revision",status==RegistryStatus.DRAFT?1L:2L);r.put("effective_at",T);r.put("created_at",T);r.put("updated_at",T.plusSeconds(1));r.put("published_at",status==RegistryStatus.DRAFT?null:T.plusSeconds(1));r.put("deprecated_at",null);r.put("sunset_at",null);r.put("deprecation_reason",null);r.put("compatibility_evidence","COMPATIBLE");r.put("breaking_approval_ref",null);return r;}
    private static Map<String,Object> profileRow(JdbcDatabaseDialect d,RegistryStatus status){var r=new LinkedHashMap<String,Object>();r.put("id",jdbc(d,PROFILE));r.put("profile_code","rsot.profile");r.put("owner_code","team.rsot");r.put("profile_version","1.0.0");r.put("status",status.name());r.put("checksum_sha256",HASH);r.put("revision",1L);r.put("created_at",T);r.put("updated_at",T);r.put("published_at",null);r.put("deprecated_at",null);r.put("sunset_at",null);r.put("deprecation_reason",null);return r;}
    private static Map<String,Object> memberRow(JdbcDatabaseDialect d){var r=new LinkedHashMap<String,Object>();r.put("position_no",1);r.put("schema_id",jdbc(d,SCHEMA));r.put("required_member",true);return r;}
    private static Object jdbc(JdbcDatabaseDialect d,DomainIdentifier id){return d==JdbcDatabaseDialect.POSTGRESQL?id.value():id.toString();}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
