package io.infranexum.adapters.persistence.jdbc;

import static io.infranexum.adapters.persistence.jdbc.JdbcScriptedSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.ddi.ipam.domain.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Deterministic JDBC coverage for all DDI/IPAM aggregate families and guards. */
final class JdbcIpamRepositoryCoverageTest {
    private static final Instant T=Instant.parse("2026-08-16T12:00:00Z");
    private static final DomainIdentifier ORG=id(1),VRF=id(2),VLAN=id(3),NET=id(4),POOL=id(5),ADDR=id(6),SITE=id(7);

    @Test void countsLocksAndExistenceChecksCoverNullableInputsAndOverlap() {
        var c=connection(query(Map.of("count",1L)),query(Map.of("count",2L)),query(Map.of("count",3L)),query(Map.of("count",4L)),
                query(Map.of("id",VRF.value())),query(Map.of("id",POOL.value())),query(Map.of("x",1)),query(List.of()),query(Map.of("x",1)),
                query(Map.of("x",1)),query(Map.of("x",1)),query(Map.of("x",1)),query(Map.of("x",1)),query(Map.of("x",1)),query(networkRow()),query(Map.of("x",1)));
        var r=new JdbcIpamRepository(dataSource(c.connection()),transaction(c.connection()),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(1,r.countVrfs());assertEquals(2,r.countVlans());assertEquals(3,r.countNetworks());assertEquals(4,r.countAddresses());
        r.lockRoutingEnvironment(ORG,VRF);r.lockPool(POOL);
        assertTrue(r.hasActiveNetworks(VRF));assertFalse(r.hasActiveNetworksForVlan(VLAN));assertTrue(r.vrfCodeExists(ORG," main "));
        assertTrue(r.vlanExists(ORG,100,null));assertTrue(r.vlanExists(ORG,null,5000L));
        assertTrue(r.networkOverlaps(ORG,VRF,new IpCidr("10.0.0.0/24"),null));
        assertTrue(r.networkOverlaps(ORG,VRF,new IpCidr("10.0.1.0/24"),NET));
        assertTrue(r.addressInUse(VRF,"10.0.0.10"));
        assertTrue(r.poolOverlaps(NET,"10.0.0.10","10.0.0.20"));
    }

    @Test void typedReadsAndCursorListsMapEveryAggregate() {
        var c=connection(query(vrfRow()),query(vlanRow()),query(networkRow()),query(poolRow()),query(addressRow()),
                query(List.of(vrfRow())),query(List.of(vlanRow())),query(List.of(networkRow())),query(List.of(poolRow())),query(List.of(addressRow())));
        var r=new JdbcIpamRepository(dataSource(c.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals("MAIN",r.vrf(VRF).orElseThrow().code());assertEquals(100,r.vlan(VLAN).orElseThrow().vlanId());
        assertEquals("10.0.0.0/24",r.network(NET).orElseThrow().cidr().value());assertEquals("10.0.0.10",r.pool(POOL).orElseThrow().startAddress());assertEquals("10.0.0.11",r.address(ADDR).orElseThrow().address());
        assertEquals(1,r.vrfs(ORG,id(99),10).size());assertEquals(1,r.vlans(ORG,null,10).size());assertEquals(1,r.networks(ORG,VRF,null,10).size());assertEquals(1,r.pools(NET,null,10).size());assertEquals(1,r.addresses(ORG,VRF,NET,null,10).size());
    }

    @Test void insertsAndUpdatesExerciseAllFamiliesIncludingReleasedToken() {
        IpamVrf vrf=IpamVrf.draft(VRF,ORG,"MAIN","Main",null,T);
        IpamVlan vlan=IpamVlan.draft(VLAN,ORG,SITE,100,null,"Users",T);
        IpamNetwork net=IpamNetwork.draft(NET,ORG,null,SITE,VRF,VLAN,null,NetworkKind.SUBNET,new IpCidr("10.0.0.0/24"),"users","trusted",T);
        IpamPool pool=IpamPool.active(POOL,ORG,NET,"10.0.0.10","10.0.0.20","dynamic",T);
        IpamAddress address=IpamAddress.assigned(ADDR,ORG,VRF,NET,POOL,"10.0.0.11",false,"host",null,null,"server",T);
        IpamAddress released=address.release(T.plusSeconds(1));
        var c=connection(update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1),update(1));
        var r=new JdbcIpamRepository(dataSource(c.connection()),transaction(c.connection()),JdbcDatabaseDialect.POSTGRESQL);
        r.insertVrf(vrf);r.updateVrf(vrf,1);r.insertVlan(vlan);r.updateVlan(vlan,1);r.insertNetwork(net);r.updateNetwork(net,1);r.insertPool(pool);r.updatePool(pool,1);r.insertAddress(address);r.updateAddress(released,1);
        assertTrue(c.parameters().getLast().values().stream().anyMatch(v->ADDR.toString().equals(v)));
    }

    @Test void notFoundVersionUniqueAndPoolBoundaryFailuresStayExplicit() {
        var missingLock=connection(query(List.of()));
        assertThrows(IpamNotFoundException.class,()->new JdbcIpamRepository(dataSource(missingLock.connection()),transaction(missingLock.connection()),JdbcDatabaseDialect.POSTGRESQL).lockPool(POOL));
        var missingNetwork=connection(query(List.of()));
        assertThrows(IpamNotFoundException.class,()->new JdbcIpamRepository(dataSource(missingNetwork.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).poolOverlaps(NET,"10.0.0.1","10.0.0.2"));
        var outside=connection(query(networkRow()));
        assertTrue(new JdbcIpamRepository(dataSource(outside.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).poolOverlaps(NET,"10.0.1.1","10.0.1.2"));
        var reverse=connection(query(networkRow()));
        assertThrows(IllegalArgumentException.class,()->new JdbcIpamRepository(dataSource(reverse.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL).poolOverlaps(NET,"10.0.0.20","10.0.0.10"));
        var unique=connection(updateFailure(new SQLException("dup","23505")));
        assertEquals("DDI_CONCURRENT_CONFLICT",assertThrows(IpamConflictException.class,()->new JdbcIpamRepository(dataSource(unique.connection()),transaction(unique.connection()),JdbcDatabaseDialect.POSTGRESQL).insertVrf(IpamVrf.draft(VRF,ORG,"MAIN","Main",null,T))).code());
        var version=connection(update(0));
        assertThrows(IpamConflictException.class,()->new JdbcIpamRepository(dataSource(version.connection()),transaction(version.connection()),JdbcDatabaseDialect.POSTGRESQL).updateVrf(IpamVrf.draft(VRF,ORG,"MAIN","Main",null,T),1));
    }

    @Test void oracleDialectExercisesAlternativeTablesAndLimits() {
        var c=connection(query(List.of(vrfRowOracle())));
        var r=new JdbcIpamRepository(dataSource(c.connection()),noTransaction(),JdbcDatabaseDialect.ORACLE);
        assertEquals(1,r.vrfs(ORG,null,5).size());assertTrue(c.sql().getFirst().contains("INFRANEXUM_DDI_VRF"));assertTrue(c.sql().getFirst().contains("FETCH NEXT ? ROWS ONLY"));
    }


    @Test void alternateNullableFiltersBindingsAndLockFailuresAreCovered() {
        var missingVrf=connection(query(List.of()));
        assertThrows(IpamNotFoundException.class, () -> new JdbcIpamRepository(
                dataSource(missingVrf.connection()),transaction(missingVrf.connection()),JdbcDatabaseDialect.POSTGRESQL)
                .lockRoutingEnvironment(ORG,VRF));

        var partialOutside=connection(query(networkRow()));
        assertTrue(new JdbcIpamRepository(dataSource(partialOutside.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .poolOverlaps(NET,"10.0.0.20","10.0.1.1"));

        var lists=connection(query(List.of(networkRow())),query(List.of(addressRow())),query(List.of(addressRow())));
        var detached=new JdbcIpamRepository(dataSource(lists.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL);
        assertEquals(1,detached.networks(ORG,null,null,10).size());
        assertEquals(1,detached.addresses(ORG,null,null,null,10).size());
        assertEquals(1,detached.addresses(ORG,VRF,null,null,10).size());

        var vxlanRow=vlanRow(); vxlanRow.put("vlan_id",null); vxlanRow.put("vni",5000L);
        var read=connection(query(vxlanRow));
        IpamVlan vxlan=new JdbcIpamRepository(dataSource(read.connection()),noTransaction(),JdbcDatabaseDialect.POSTGRESQL)
                .vlan(VLAN).orElseThrow();
        assertNull(vxlan.vlanId()); assertEquals(5000L,vxlan.vni());

        IpamVlan vxlanValue=IpamVlan.draft(VLAN,ORG,SITE,null,5000L,"Overlay",T);
        var writes=connection(update(1),update(1));
        var repository=new JdbcIpamRepository(dataSource(writes.connection()),transaction(writes.connection()),JdbcDatabaseDialect.POSTGRESQL);
        repository.insertVlan(vxlanValue);
        repository.insertVrf(IpamVrf.draft(id(8),ORG,"BLUE","Blue","65000:8",T));

        var zero=connection(update(0));
        assertThrows(JdbcPersistenceException.class, () -> new JdbcIpamRepository(dataSource(zero.connection()),
                transaction(zero.connection()),JdbcDatabaseDialect.POSTGRESQL)
                .insertVrf(IpamVrf.draft(id(9),ORG,"ZERO","Zero",null,T)));
    }

    private static Map<String,Object> vrfRow(){return row(Map.of("id",VRF.value(),"organization_id",ORG.value(),"code","MAIN","display_name","Main","route_distinguisher","65000:1","lifecycle_status","ACTIVE","version",1L,"created_at",T,"updated_at",T));}
    private static Map<String,Object> vrfRowOracle(){var r=vrfRow();r.put("id",VRF.toString());r.put("organization_id",ORG.toString());return r;}
    private static Map<String,Object> vlanRow(){var r=new LinkedHashMap<String,Object>();r.put("id",VLAN.value());r.put("organization_id",ORG.value());r.put("site_id",SITE.value());r.put("vlan_id",100);r.put("vni",null);r.put("name","Users");r.put("lifecycle_status","ACTIVE");r.put("version",1L);r.put("created_at",T);r.put("updated_at",T);return r;}
    private static Map<String,Object> networkRow(){var r=new LinkedHashMap<String,Object>();r.put("id",NET.value());r.put("organization_id",ORG.value());r.put("subdivision_id",null);r.put("site_id",SITE.value());r.put("vrf_id",VRF.value());r.put("vlan_id",VLAN.value());r.put("parent_network_id",null);r.put("network_kind","SUBNET");r.put("cidr_value","10.0.0.0/24");r.put("usage_text","users");r.put("trust_level","trusted");r.put("lifecycle_status","ACTIVE");r.put("version",1L);r.put("created_at",T);r.put("updated_at",T);return r;}
    private static Map<String,Object> poolRow(){var r=new LinkedHashMap<String,Object>();r.put("id",POOL.value());r.put("organization_id",ORG.value());r.put("network_id",NET.value());r.put("start_address","10.0.0.10");r.put("end_address","10.0.0.20");r.put("allocation_cursor","10.0.0.10");r.put("name","dynamic");r.put("lifecycle_status","ACTIVE");r.put("version",1L);r.put("created_at",T);r.put("updated_at",T);return r;}
    private static Map<String,Object> addressRow(){var r=new LinkedHashMap<String,Object>();r.put("id",ADDR.value());r.put("organization_id",ORG.value());r.put("vrf_id",VRF.value());r.put("network_id",NET.value());r.put("pool_id",POOL.value());r.put("address_value","10.0.0.11");r.put("lifecycle_status","ALLOCATED");r.put("hostname","host");r.put("rsot_object_id",null);r.put("dcim_equipment_id",null);r.put("purpose","server");r.put("version",1L);r.put("created_at",T);r.put("updated_at",T);return r;}
    private static LinkedHashMap<String,Object> row(Map<String,Object> values){return new LinkedHashMap<>(values);}
    private static DomainIdentifier id(int n){return new DomainIdentifier(UUID.fromString("018bcfe5-6800-7000-8000-%012d".formatted(n)));}
}
