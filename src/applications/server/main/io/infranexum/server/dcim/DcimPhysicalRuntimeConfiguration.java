package io.infranexum.server.dcim;

import io.infranexum.adapters.persistence.jdbc.*;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.dcim.physical.application.DcimPhysicalApplicationService;
import io.infranexum.dcim.physical.ports.DcimPhysicalFeaturePolicy;
import io.infranexum.server.platform.PlatformCapabilityService;
import io.infranexum.server.dcim.cli.DcimPhysicalCli;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable composition root for PGM-07-E05 DCIM rack/equipment/cabling. */
@Configuration(proxyBeanMethods=false)
public class DcimPhysicalRuntimeConfiguration {
 @Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="infranexum.persistence.mode",havingValue="POSTGRESQL") static class PostgreSQL { @Bean DcimPhysicalApplicationService dcimPhysicalApplicationService(DataSource dataSource,TransactionalEventStore eventStore,PlatformCapabilityService capabilities,@Qualifier("platformClock") Clock clock,@Qualifier("correlationIdentifiers") UuidV7Generator ids){return service(dataSource,eventStore,capabilities,clock,ids,JdbcDatabaseDialect.POSTGRESQL);} }
 @Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="infranexum.persistence.mode",havingValue="ORACLE") static class Oracle { @Bean DcimPhysicalApplicationService dcimPhysicalApplicationService(DataSource dataSource,TransactionalEventStore eventStore,PlatformCapabilityService capabilities,@Qualifier("platformClock") Clock clock,@Qualifier("correlationIdentifiers") UuidV7Generator ids){return service(dataSource,eventStore,capabilities,clock,ids,JdbcDatabaseDialect.ORACLE);} }
 @Bean @ConditionalOnBean(DcimPhysicalApplicationService.class) DcimPhysicalCli dcimPhysicalCli(LocalAuthenticationService authentication,RbacAuthorizationService authorization,PolicyDecisionService policy,IdentityAccessFeaturePolicy features,PlatformCapabilityService capabilities,DcimPhysicalApplicationService service,@Qualifier("correlationIdentifiers") UuidV7Generator ids){return new DcimPhysicalCli(authentication,authorization,policy,features,capabilities,service,ids);}
 private static DcimPhysicalApplicationService service(DataSource dataSource,TransactionalEventStore eventStore,PlatformCapabilityService capabilities,@Qualifier("platformClock") Clock clock,UuidV7Generator ids,JdbcDatabaseDialect dialect){if(!(eventStore instanceof JdbcTransactionalEventStore tx))throw new IllegalStateException("DCIM physical infrastructure requires durable JDBC transactional events");var organizations=new JdbcOrganizationRepository(dataSource,tx,dialect);var subdivisions=new JdbcSubdivisionRepository(dataSource,tx,dialect);var facilities=new JdbcFacilityRepository(dataSource,tx,dialect);var partners=new JdbcPartnerRepository(dataSource,tx,dialect);var rsot=new JdbcRsotRepository(dataSource,dialect);var assets=new JdbcAssetRepository(dataSource,tx,dialect);return new DcimPhysicalApplicationService(new JdbcDcimPhysicalRepository(dataSource,tx,dialect),new JdbcDcimPhysicalIdempotencyRepository(tx,dialect),featurePolicy(capabilities),new DcimPhysicalReferencePolicyAdapter(organizations,subdivisions,facilities,partners,rsot,assets),eventStore,ids,clock);}
 static DcimPhysicalFeaturePolicy featurePolicy(PlatformCapabilityService capabilities){return new DcimPhysicalFeaturePolicy(){public boolean physicalEnabled(){return capabilities.explain("dcim.physical").available() && capabilities.explain("dcim.facilities").available();} public long rackLimit(){return capabilities.quotaPlan().limit("dcim.racks.max");} public long portLimit(){return capabilities.quotaPlan().limit("dcim.ports.max");} public long connectionLimit(){return capabilities.quotaPlan().limit("dcim.connections.max");}};}
}
