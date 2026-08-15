package io.infranexum.adapters.persistence.jdbc;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.dcim.facility.application.FacilityPage;
import io.infranexum.dcim.facility.application.FacilitySearchCriteria;
import io.infranexum.dcim.facility.domain.FacilityCode;
import io.infranexum.dcim.facility.domain.FacilityConflictException;
import io.infranexum.dcim.facility.domain.FacilityKind;
import io.infranexum.dcim.facility.domain.FacilityNode;
import io.infranexum.dcim.facility.domain.FacilityStatus;
import io.infranexum.dcim.facility.ports.FacilityRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** PostgreSQL/Oracle repository for the PGM-07-E04 DCIM physical hierarchy. */
public final class JdbcFacilityRepository implements FacilityRepository {
    private static final String COLUMNS = "id,facility_kind,organization_id,subdivision_id,parent_id,scope_id,code,display_name,"
            + "lifecycle_status,address_line_1,address_line_2,postal_code,city,country_code,timezone,latitude,longitude,floor_count,level_number,area_m2,level_height_m,capacity_kw,"
            + "access_restriction,zone_type,description,version,created_at,updated_at,created_by,updated_by,last_reason";

    private final DataSource dataSource;
    private final JdbcConnectionAccess transaction;
    private final JdbcDatabaseDialect dialect;

    public JdbcFacilityRepository(DataSource dataSource, JdbcConnectionAccess transaction, JdbcDatabaseDialect dialect) {
        this.dataSource=Objects.requireNonNull(dataSource,"dataSource");
        this.transaction=Objects.requireNonNull(transaction,"transaction");
        this.dialect=Objects.requireNonNull(dialect,"dialect");
    }

    @Override public long count(FacilityKind kind) {
        Objects.requireNonNull(kind,"kind");
        try(PreparedStatement s=transaction.requireCurrentConnection().prepareStatement("SELECT COUNT(*) FROM "+table()+" WHERE facility_kind=?")){
            s.setString(1,kind.name()); try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("facility count returned no row");return r.getLong(1);} }
        catch(SQLException failure){throw fail("count DCIM facilities",failure);}
    }

    @Override public boolean existsByScopeCode(FacilityKind kind, DomainIdentifier scopeId, FacilityCode code) {
        Objects.requireNonNull(kind,"kind");Objects.requireNonNull(scopeId,"scopeId");Objects.requireNonNull(code,"code");
        try(PreparedStatement s=transaction.requireCurrentConnection().prepareStatement("SELECT 1 FROM "+table()+" WHERE facility_kind=? AND scope_id=? AND code=?")){
            s.setString(1,kind.name()); dialect.bindIdentifier(s,2,scopeId); s.setString(3,code.value()); try(ResultSet r=s.executeQuery()){return r.next();}}
        catch(SQLException failure){throw fail("check DCIM facility code",failure);}
    }

    @Override public Optional<FacilityNode> findById(DomainIdentifier id) {
        Objects.requireNonNull(id,"id"); Connection current=currentConnectionOrNull(); if(current!=null)return findById(current,id);
        try(Connection c=dataSource.getConnection()){return findById(c,id);}catch(SQLException failure){throw fail("find DCIM facility",failure);}
    }

    @Override public long activeBuildingsForSite(DomainIdentifier siteId) {
        Objects.requireNonNull(siteId,"siteId");
        String sql="SELECT COUNT(*) FROM "+table()+" WHERE parent_id=? AND facility_kind='BUILDING' AND lifecycle_status='ACTIVE'";
        Connection current=currentConnectionOrNull();
        try {
            if(current!=null) return countChildren(current,sql,siteId);
            try(Connection c=dataSource.getConnection()){return countChildren(c,sql,siteId);}
        } catch(SQLException failure){throw fail("count active DCIM site buildings",failure);}
    }

    @Override public void insert(FacilityNode node) {
        Objects.requireNonNull(node,"node");
        String sql="INSERT INTO "+table()+" ("+COLUMNS+") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try(PreparedStatement s=transaction.requireCurrentConnection().prepareStatement(sql)){bind(s,node);if(s.executeUpdate()!=1)throw new SQLException("facility insert affected unexpected rows");}
        catch(SQLException failure){if(dialect.isUniqueViolation(failure))throw new FacilityConflictException("DCIM_CODE_DUPLICATE","facility code or identifier was committed concurrently");throw fail("insert DCIM facility",failure);}
    }

    @Override public void update(FacilityNode node,long expectedVersion) {
        Objects.requireNonNull(node,"node");
        String sql="UPDATE "+table()+" SET display_name=?,lifecycle_status=?,address_line_1=?,address_line_2=?,postal_code=?,city=?,country_code=?,timezone=?,latitude=?,longitude=?,floor_count=?,level_number=?,area_m2=?,level_height_m=?,capacity_kw=?,access_restriction=?,zone_type=?,description=?,version=?,updated_at=?,updated_by=?,last_reason=? WHERE id=? AND version=?";
        try(PreparedStatement s=transaction.requireCurrentConnection().prepareStatement(sql)){
            int i=1;s.setString(i++,node.displayName());s.setString(i++,node.status().name());setNullableString(s,i++,node.addressLine1());setNullableString(s,i++,node.addressLine2());setNullableString(s,i++,node.postalCode());setNullableString(s,i++,node.city());setNullableString(s,i++,node.countryCode());setNullableString(s,i++,node.timezone());s.setBigDecimal(i++,node.latitude());s.setBigDecimal(i++,node.longitude());setNullableInt(s,i++,node.floorCount());setNullableInt(s,i++,node.levelNumber());s.setBigDecimal(i++,node.areaM2());s.setBigDecimal(i++,node.levelHeightM());s.setBigDecimal(i++,node.capacityKw());setNullableString(s,i++,node.accessRestriction());setNullableString(s,i++,node.zoneType());setNullableString(s,i++,node.description());s.setLong(i++,node.version());JdbcTemporal.bindInstant(s,i++,node.updatedAt());dialect.bindIdentifier(s,i++,node.updatedBy());s.setString(i++,node.lastReason());dialect.bindIdentifier(s,i++,node.id());s.setLong(i,expectedVersion);
            if(s.executeUpdate()!=1)throw new FacilityConflictException("VERSION_CONFLICT","facility version changed");
        }catch(SQLException failure){throw fail("update DCIM facility",failure);}
    }

    @Override public FacilityPage search(FacilitySearchCriteria criteria) {
        Objects.requireNonNull(criteria,"criteria");StringBuilder sql=new StringBuilder("SELECT ").append(COLUMNS).append(" FROM ").append(table()).append(" f WHERE 1=1");List<Binder> binders=new ArrayList<>();
        if(criteria.organizationId()!=null){sql.append(" AND f.organization_id=?");binders.add((s,i)->dialect.bindIdentifier(s,i,criteria.organizationId()));}
        if(criteria.subdivisionId()!=null){sql.append(" AND f.subdivision_id=?");binders.add((s,i)->dialect.bindIdentifier(s,i,criteria.subdivisionId()));}
        if(criteria.kind()!=null){sql.append(" AND f.facility_kind=?");binders.add((s,i)->s.setString(i,criteria.kind().name()));}
        if(criteria.parentId()!=null){sql.append(" AND f.parent_id=?");binders.add((s,i)->dialect.bindIdentifier(s,i,criteria.parentId()));}
        if(criteria.status()!=null){sql.append(" AND f.lifecycle_status=?");binders.add((s,i)->s.setString(i,criteria.status().name()));}
        if(criteria.countryCode()!=null){sql.append(" AND f.country_code=?");binders.add((s,i)->s.setString(i,criteria.countryCode()));}
        if(criteria.afterId()!=null){sql.append(" AND f.id>?");binders.add((s,i)->dialect.bindIdentifier(s,i,criteria.afterId()));}
        sql.append(" ORDER BY f.id").append(dialect==JdbcDatabaseDialect.POSTGRESQL?" LIMIT ?":" FETCH NEXT ? ROWS ONLY");
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement(sql.toString())){int i=1;for(Binder b:binders)b.bind(s,i++);s.setInt(i,criteria.limit()+1);List<FacilityNode> items=new ArrayList<>();try(ResultSet r=s.executeQuery()){while(r.next())items.add(read(r));}boolean more=items.size()>criteria.limit();if(more)items=new ArrayList<>(items.subList(0,criteria.limit()));DomainIdentifier next=more&&!items.isEmpty()?items.get(items.size()-1).id():null;return new FacilityPage(items,next);}
        catch(SQLException failure){throw fail("search DCIM facilities",failure);}
    }

    private Optional<FacilityNode> findById(Connection c,DomainIdentifier id){try(PreparedStatement s=c.prepareStatement("SELECT "+COLUMNS+" FROM "+table()+" WHERE id=?")){dialect.bindIdentifier(s,1,id);try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(read(r)):Optional.empty();}}catch(SQLException failure){throw fail("find DCIM facility",failure);}}
    private long countChildren(Connection c,String sql,DomainIdentifier id)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){dialect.bindIdentifier(s,1,id);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("child count returned no row");return r.getLong(1);}}}

    private FacilityNode read(ResultSet r)throws SQLException{return FacilityNode.restore(dialect.readIdentifier(r,"id"),FacilityKind.valueOf(r.getString("facility_kind")),dialect.readIdentifier(r,"organization_id"),dialect.readIdentifier(r,"subdivision_id"),nullableId(r,"parent_id"),dialect.readIdentifier(r,"scope_id"),new FacilityCode(r.getString("code")),r.getString("display_name"),FacilityStatus.valueOf(r.getString("lifecycle_status")),r.getString("address_line_1"),r.getString("address_line_2"),r.getString("postal_code"),r.getString("city"),r.getString("country_code"),r.getString("timezone"),r.getBigDecimal("latitude"),r.getBigDecimal("longitude"),nullableInt(r,"floor_count"),nullableInt(r,"level_number"),r.getBigDecimal("area_m2"),r.getBigDecimal("level_height_m"),r.getBigDecimal("capacity_kw"),r.getString("access_restriction"),r.getString("zone_type"),r.getString("description"),r.getLong("version"),JdbcTemporal.readRequired(r,"created_at"),JdbcTemporal.readRequired(r,"updated_at"),dialect.readIdentifier(r,"created_by"),dialect.readIdentifier(r,"updated_by"),r.getString("last_reason"));}
    private void bind(PreparedStatement s,FacilityNode n)throws SQLException{int i=1;dialect.bindIdentifier(s,i++,n.id());s.setString(i++,n.kind().name());dialect.bindIdentifier(s,i++,n.organizationId());dialect.bindIdentifier(s,i++,n.subdivisionId());dialect.bindNullableIdentifier(s,i++,n.parentId());dialect.bindIdentifier(s,i++,n.scopeId());s.setString(i++,n.code().value());s.setString(i++,n.displayName());s.setString(i++,n.status().name());setNullableString(s,i++,n.addressLine1());setNullableString(s,i++,n.addressLine2());setNullableString(s,i++,n.postalCode());setNullableString(s,i++,n.city());setNullableString(s,i++,n.countryCode());setNullableString(s,i++,n.timezone());s.setBigDecimal(i++,n.latitude());s.setBigDecimal(i++,n.longitude());setNullableInt(s,i++,n.floorCount());setNullableInt(s,i++,n.levelNumber());s.setBigDecimal(i++,n.areaM2());s.setBigDecimal(i++,n.levelHeightM());s.setBigDecimal(i++,n.capacityKw());setNullableString(s,i++,n.accessRestriction());setNullableString(s,i++,n.zoneType());setNullableString(s,i++,n.description());s.setLong(i++,n.version());JdbcTemporal.bindInstant(s,i++,n.createdAt());JdbcTemporal.bindInstant(s,i++,n.updatedAt());dialect.bindIdentifier(s,i++,n.createdBy());dialect.bindIdentifier(s,i++,n.updatedBy());s.setString(i,n.lastReason());}
    private DomainIdentifier nullableId(ResultSet r,String c)throws SQLException{Object value=r.getObject(c);return value==null?null:dialect.readIdentifier(r,c);} private static Integer nullableInt(ResultSet r,String c)throws SQLException{int v=r.getInt(c);return r.wasNull()?null:v;}
    private static void setNullableInt(PreparedStatement s,int i,Integer v)throws SQLException{if(v==null)s.setNull(i,Types.INTEGER);else s.setInt(i,v);} private static void setNullableString(PreparedStatement s,int i,String v)throws SQLException{if(v==null)s.setNull(i,Types.VARCHAR);else s.setString(i,v);}
    private Connection currentConnectionOrNull(){try{return transaction.requireCurrentConnection();}catch(IllegalStateException noTransaction){return null;}}
    private String table(){return dialect==JdbcDatabaseDialect.POSTGRESQL?"infranexum_dcim.facility_node":"INFRANEXUM_DCIM_FACILITY";}
    private static JdbcPersistenceException fail(String op,SQLException failure){return new JdbcPersistenceException(op,failure);} @FunctionalInterface private interface Binder{void bind(PreparedStatement statement,int index)throws SQLException;}
}
