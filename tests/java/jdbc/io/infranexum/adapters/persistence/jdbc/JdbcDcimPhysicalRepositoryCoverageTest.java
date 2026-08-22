package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.physical.domain.*;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic JDBC coverage for DCIM racks, equipment, ports and cables. */
final class JdbcDcimPhysicalRepositoryCoverageTest {
    private static final Instant T=Instant.parse("2026-08-16T12:00:00Z");
    private static final DomainIdentifier ORG=id(1),SUB=id(2),ROOM=id(3),MFR=id(4),MODEL=id(5),RACK=id(6),EQUIP=id(7),RSOT=id(8),ASSET=id(9),PORTA=id(10),PORTB=id(11),CABLE=id(12),ACTOR=id(13);

    @Test void countsExistenceAndLocksCoverPositiveNegativeAndOrderingBranches(){
        var c=connection(query(Map.of("count",1L)),query(Map.of("count",2L)),query(Map.of("count",3L)),
                query(Map.of("x",1)),query(List.of()),query(Map.of("x",1)),query(Map.of("x",1)),query(List.of()),
                query(Map.of("id",RACK.value())),query(Map.of("id",PORTA.value())),query(Map.of("id",PORTB.value())));
        var r=new JdbcDcimPhysicalRepository(dataSource(c.connection()),transaction(c.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(1,r.rackCount());assertEquals(2,r.portCount());assertEquals(3,r.activeConnectionCount());
        assertTrue(r.modelCodeExists(ORG,MFR," model1 "));assertFalse(r.rackCodeExists(ROOM,"R01"));
        assertFalse(r.serialExists(null));assertFalse(r.serialExists(" "));assertTrue(r.serialExists(" SN1 "));
        assertTrue(r.footprintOccupied(RACK,1,2,EQUIP));assertFalse(r.equipmentHasActiveCable(EQUIP));
        r.lockRackForOccupancy(RACK);r.lockPortsForConnection(PORTB,PORTA);
        assertTrue(c.sql().stream().anyMatch(sql->sql.contains("FOR UPDATE")));
    }

    @Test void typedReadsHydrateModelTemplatesRackEquipmentPortAndCable(){
        var c=connection(query(modelRow()),query(List.of(templateRow())),query(rackRow()),query(equipmentRow()),
                query(portRow()),query(Map.of("x",1)),query(cableRow()));
        var r=new JdbcDcimPhysicalRepository(dataSource(c.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(1,r.model(MODEL).orElseThrow().portTemplates().size());assertEquals("R01",r.rack(RACK).orElseThrow().code());
        assertEquals("SN1",r.equipment(EQUIP).orElseThrow().serialNumber());assertTrue(r.port(PORTA).orElseThrow().connected());assertEquals("C1",r.cable(CABLE).orElseThrow().label());
    }

    @Test void listsExercisePaginationOptionalFiltersAndOracleDialect(){
        var pg=connection(query(List.of(modelRow())),query(List.of(templateRow())),query(List.of(rackRow())),query(List.of(equipmentRow())),
                query(List.of(portRow())),query(List.of()),query(List.of(cableRow())));
        var r=new JdbcDcimPhysicalRepository(dataSource(pg.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(1,r.models(ORG,0,10).size());assertEquals(1,r.racks(ORG,ROOM,RACK,10).size());assertEquals(1,r.equipment(ORG,RACK,EQUIP,10).size());assertEquals(1,r.ports(EQUIP,0,10).size());assertEquals(1,r.cables(ORG,CABLE,10).size());
        assertTrue(pg.sql().stream().anyMatch(sql->sql.contains("LIMIT ? OFFSET ?")));

        var oracle=connection(query(List.of()));
        assertTrue(new JdbcDcimPhysicalRepository(dataSource(oracle.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE).models(ORG,1,2).isEmpty());
        assertTrue(oracle.sql().getFirst().contains("INFRANEXUM_DCIM_MODEL"));assertTrue(oracle.sql().getFirst().contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"));
    }

    @Test void writesExerciseAllAggregateFamiliesAndVersionGuards(){
        EquipmentModel model=EquipmentModel.draft(MODEL,ORG,MFR,"MODEL1","Model","rack",2,482,700,BigDecimal.ONE,List.of(new PortTemplate("eth",2,PortKind.NETWORK,"copper","rj45")),null,ACTOR,"create",T);
        Rack rack=Rack.draft(RACK,ORG,SUB,ROOM,"R01","Rack",42,600,1000,ACTOR,"create",T);
        Equipment equipment=Equipment.installed(EQUIP,ORG,SUB,RACK,MODEL,RSOT,ASSET,"SN1","A1",1,"front",ACTOR,"install",T);
        PhysicalPort a=new PhysicalPort(PORTA,ORG,EQUIP,"eth0",PortKind.NETWORK,"copper","rj45",false);
        PhysicalPort b=new PhysicalPort(PORTB,ORG,EQUIP,"eth1",PortKind.NETWORK,"copper","rj45",false);
        CableConnection cable=CableConnection.active(CABLE,ORG,SUB,PORTA,PORTB,"C1","copper","rj45",ACTOR,"connect",T);
        var c=connection(update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1));
        var r=new JdbcDcimPhysicalRepository(dataSource(c.connection()),transaction(c.connection()),JdbcDatabaseDialect.POSTGRESQL);
        r.insertModel(model);r.updateModel(model,1);r.insertRack(rack);r.updateRack(rack,1);r.insertEquipment(equipment,List.of(a,b));r.updateEquipment(equipment,1);r.insertCable(cable);r.updateCable(cable,1);
        assertTrue(c.sql().stream().anyMatch(sql->sql.contains("model_port_template")));assertTrue(c.sql().stream().anyMatch(sql->sql.contains("physical_port")));
    }

    @Test void uniquenessVersionMissingLockAndSqlFailuresStayExplicit(){
        var unique=connection(updateFailure(new SQLException("dup","23505")));
        var ru=new JdbcDcimPhysicalRepository(dataSource(unique.connection()),transaction(unique.connection()),JdbcDatabaseDialect.POSTGRESQL);
        var rack=Rack.draft(RACK,ORG,SUB,ROOM,"R01","Rack",42,600,1000,ACTOR,"create",T);
        assertEquals("DCIM_RACK_DUPLICATE",assertThrows(DcimPhysicalConflictException.class,()->ru.insertRack(rack)).code());
        var version=connection(update(0));
        assertEquals("VERSION_CONFLICT",assertThrows(DcimPhysicalConflictException.class,()->new JdbcDcimPhysicalRepository(dataSource(version.connection()),transaction(version.connection()),JdbcDatabaseDialect.POSTGRESQL).updateRack(rack,1)).code());
        var missing=connection(query(List.of()));
        assertThrows(DcimPhysicalNotFoundException.class,()->new JdbcDcimPhysicalRepository(dataSource(missing.connection()),transaction(missing.connection()),JdbcDatabaseDialect.POSTGRESQL).lockRackForOccupancy(RACK));
        var failed=connection(queryFailure(new SQLException("offline","08006")));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(failed.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).rackCount());
    }


    @Test void optionalListFiltersSharedReadsCountAndInsertGuardsAreCovered(){
        var lists=connection(query(List.of()),query(List.of()),query(List.of()));
        var detached=new JdbcDcimPhysicalRepository(dataSource(lists.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL);
        assertTrue(detached.racks(ORG,null,null,10).isEmpty());
        assertTrue(detached.equipment(ORG,null,null,10).isEmpty());
        assertTrue(detached.cables(ORG,null,10).isEmpty());

        var footprint=connection(query(List.of()));
        assertFalse(new JdbcDcimPhysicalRepository(dataSource(footprint.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .footprintOccupied(RACK,1,2,null));

        var noCount=connection(query(List.of()));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcDcimPhysicalRepository(dataSource(noCount.connection()),
                transaction(noCount.connection()),JdbcDatabaseDialect.POSTGRESQL).rackCount());

        var shared=connection(query(rackRow()));
        assertEquals(RACK,new JdbcDcimPhysicalRepository(dataSource(shared.connection()),transaction(shared.connection()),
                JdbcDatabaseDialect.POSTGRESQL).rack(RACK).orElseThrow().id());

        EquipmentModel model=EquipmentModel.draft(MODEL,ORG,MFR,"MODEL1","Model","rack",2,482,700,
                BigDecimal.ONE,List.of(new PortTemplate("eth",1,PortKind.NETWORK,"copper","rj45")),null,ACTOR,"create",T);
        var zero=connection(update(0));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcDcimPhysicalRepository(dataSource(zero.connection()),
                transaction(zero.connection()),JdbcDatabaseDialect.POSTGRESQL).insertModel(model));
    }

    @Test void everyReadAndListBoundaryTranslatesSqlFailureWithoutLeakingDriverExceptions(){
        SQLException offline=new SQLException("offline","08006");
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).model(MODEL));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).rack(RACK));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).equipment(EQUIP));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).port(PORTA));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).cable(CABLE));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).models(ORG,0,10));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).racks(ORG,null,null,10));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).equipment(ORG,null,null,10));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).ports(EQUIP,0,10));
        assertThrows(JdbcPersistenceException.class,()->new JdbcDcimPhysicalRepository(dataSource(connection(queryFailure(offline)).connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).cables(ORG,null,10));
    }

    @Test void everyVersionedAggregateMutationRejectsAffectedRowMismatch(){
        EquipmentModel model=EquipmentModel.draft(MODEL,ORG,MFR,"MODEL1","Model","rack",2,482,700,BigDecimal.ONE,List.of(new PortTemplate("eth",1,PortKind.NETWORK,"copper","rj45")),null,ACTOR,"create",T);
        Rack rack=Rack.draft(RACK,ORG,SUB,ROOM,"R01","Rack",42,600,1000,ACTOR,"create",T);
        Equipment equipment=Equipment.installed(EQUIP,ORG,SUB,RACK,MODEL,RSOT,ASSET,"SN1","A1",1,"front",ACTOR,"install",T);
        CableConnection cable=CableConnection.active(CABLE,ORG,SUB,PORTA,PORTB,"C1","copper","rj45",ACTOR,"connect",T);
        var modelZero=connection(update(0));
        assertEquals("VERSION_CONFLICT",assertThrows(DcimPhysicalConflictException.class,()->new JdbcDcimPhysicalRepository(dataSource(modelZero.connection()),transaction(modelZero.connection()),JdbcDatabaseDialect.POSTGRESQL).updateModel(model,1)).code());
        var rackZero=connection(update(0));
        assertEquals("VERSION_CONFLICT",assertThrows(DcimPhysicalConflictException.class,()->new JdbcDcimPhysicalRepository(dataSource(rackZero.connection()),transaction(rackZero.connection()),JdbcDatabaseDialect.POSTGRESQL).updateRack(rack,1)).code());
        var equipmentZero=connection(update(0));
        assertEquals("VERSION_CONFLICT",assertThrows(DcimPhysicalConflictException.class,()->new JdbcDcimPhysicalRepository(dataSource(equipmentZero.connection()),transaction(equipmentZero.connection()),JdbcDatabaseDialect.POSTGRESQL).updateEquipment(equipment,1)).code());
        var cableZero=connection(update(0));
        assertEquals("VERSION_CONFLICT",assertThrows(DcimPhysicalConflictException.class,()->new JdbcDcimPhysicalRepository(dataSource(cableZero.connection()),transaction(cableZero.connection()),JdbcDatabaseDialect.POSTGRESQL).updateCable(cable,1)).code());
    }

    private static Map<String,Object> modelRow(){var r=base(MODEL);r.put("manufacturer_partner_id",MFR.value());r.put("code","MODEL1");r.put("display_name","Model");r.put("equipment_category","OTHER");r.put("equipment_type","OTHER_EQUIPMENT");r.put("manufacturer_reference",null);r.put("form_factor","rack");r.put("rack_units",2);r.put("width_mm",482);r.put("depth_mm",700);r.put("weight_kg",BigDecimal.ONE);r.put("description",null);return r;}
    private static Map<String,Object> templateRow(){return Map.of("name_prefix","eth","port_count",2,"port_kind","NETWORK","media","copper","connector","rj45");}
    private static Map<String,Object> rackRow(){var r=base(RACK);r.put("subdivision_id",SUB.value());r.put("room_id",ROOM.value());r.put("code","R01");r.put("display_name","Rack");r.put("height_u",42);r.put("width_mm",600);r.put("depth_mm",1000);return r;}
    private static Map<String,Object> equipmentRow(){var r=base(EQUIP);r.put("subdivision_id",SUB.value());r.put("rack_id",RACK.value());r.put("model_id",MODEL.value());r.put("rsot_object_id",RSOT.value());r.put("itam_asset_id",ASSET.value());r.put("serial_number","SN1");r.put("asset_tag","A1");r.put("start_u",1);r.put("face","front");return r;}
    private static Map<String,Object> portRow(){var r=new LinkedHashMap<String,Object>();r.put("id",PORTA.value());r.put("organization_id",ORG.value());r.put("equipment_id",EQUIP.value());r.put("port_name","eth0");r.put("port_kind","NETWORK");r.put("media","copper");r.put("connector","rj45");return r;}
    private static Map<String,Object> cableRow(){var r=base(CABLE);r.put("subdivision_id",SUB.value());r.put("port_a_id",PORTA.value());r.put("port_b_id",PORTB.value());r.put("label","C1");r.put("media","copper");r.put("connector","rj45");r.put("cable_type","OTHER");r.put("length_meters",BigDecimal.ONE);r.put("manufacturer_partner_id",null);r.put("manufacturer_reference",null);return r;}
    private static LinkedHashMap<String,Object> base(DomainIdentifier id){var r=new LinkedHashMap<String,Object>();r.put("id",id.value());r.put("organization_id",ORG.value());r.put("lifecycle_status","ACTIVE");r.put("version",1L);r.put("created_at",T);r.put("updated_at",T);r.put("created_by",ACTOR.value());r.put("updated_by",ACTOR.value());r.put("last_reason","create");return r;}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
