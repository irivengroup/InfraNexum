package io.infranexum.server.ddi;

import io.infranexum.adapters.persistence.jdbc.*;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.ddi.ipam.application.IpamApplicationService;
import io.infranexum.ddi.ipam.ports.IpamFeaturePolicy;
import io.infranexum.server.platform.PlatformCapabilityService;
import io.infranexum.server.ddi.cli.IpamCli;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.identity.access.application.*;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Durable Server composition for PGM-08-E01 DDI/IPAM. */
@Configuration(proxyBeanMethods=false)
public class IpamRuntimeConfiguration {
 @Bean @ConditionalOnProperty(name="infranexum.ddi.ipam-api-enabled",havingValue="true") IpamApplicationService ipamApplicationService(DataSource ds,TransactionalEventStore store,PlatformCapabilityService capabilities,@Qualifier("platformClock") Clock clock,@Qualifier("correlationIdentifiers") UuidV7Generator ids,@org.springframework.beans.factory.annotation.Value("${infranexum.persistence.mode:POSTGRESQL}") String mode){if(!(store instanceof JdbcTransactionalEventStore events))throw new IllegalStateException("DDI/IPAM requires durable JDBC transactional events");JdbcDatabaseDialect dialect=JdbcDatabaseDialect.valueOf(mode.toUpperCase(java.util.Locale.ROOT));var orgs=new JdbcOrganizationRepository(ds,events,dialect);var subs=new JdbcSubdivisionRepository(ds,events,dialect);var facilities=new JdbcFacilityRepository(ds,events,dialect);var rsot=new JdbcRsotRepository(ds,dialect);var physical=new JdbcDcimPhysicalRepository(ds,events,dialect);return new IpamApplicationService(new JdbcIpamRepository(ds,events,dialect),new JdbcIpamIdempotencyRepository(events,dialect),feature(capabilities),new IpamReferencePolicyAdapter(orgs,subs,facilities,rsot,physical),store,ids,clock);}
 @Bean @ConditionalOnProperty(name="infranexum.ddi.ipam-api-enabled",havingValue="true") IpamCli ipamCli(LocalAuthenticationService authentication,RbacAuthorizationService authorization,PolicyDecisionService policy,IdentityAccessFeaturePolicy features,PlatformCapabilityService capabilities,IpamApplicationService service,@Qualifier("correlationIdentifiers") UuidV7Generator ids){return new IpamCli(authentication,authorization,policy,features,capabilities,service,ids);}
 static IpamFeaturePolicy feature(PlatformCapabilityService c){return new IpamFeaturePolicy(){public boolean ipamEnabled(){return c.explain("ddi.ipam").available();}public long vrfLimit(){return c.quotaPlan().limit("ddi.vrfs.max");}public long vlanLimit(){return c.quotaPlan().limit("ddi.vlans.max");}public long prefixLimit(){return c.quotaPlan().limit("ddi.prefixes.max");}public long addressLimit(){return c.quotaPlan().limit("ddi.ip_addresses.max");}};}
}
