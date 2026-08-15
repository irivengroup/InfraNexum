package io.infranexum.server.itam;

import io.infranexum.adapters.persistence.jdbc.JdbcAssetRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcComplianceIdempotencyRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcComplianceRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcSubdivisionRepository;
import io.infranexum.adapters.persistence.jdbc.JdbcTransactionalEventStore;
import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.core.events.TransactionalEventStore;
import io.infranexum.itam.compliance.application.ComplianceApplicationService;
import io.infranexum.identity.access.application.PolicyDecisionService;
import io.infranexum.identity.access.application.RbacAuthorizationService;
import io.infranexum.identity.access.ports.IdentityAccessFeaturePolicy;
import io.infranexum.identity.local.application.LocalAuthenticationService;
import io.infranexum.server.itam.cli.ItamComplianceCli;
import io.infranexum.itam.asset.application.AssetApplicationService;
import io.infranexum.itam.compliance.ports.ComplianceFeaturePolicy;
import io.infranexum.itam.partner.application.PartnerApplicationService;
import io.infranexum.rsot.application.RsotQueryService;
import io.infranexum.server.platform.PlatformCapabilityService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/** Durable runtime composition for PGM-07-E03 warranties, support coverage, software licenses and deadline alerts. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnBean({PartnerApplicationService.class,RsotQueryService.class})
public class ItamComplianceRuntimeConfiguration {
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="infranexum.persistence.mode",havingValue="POSTGRESQL")
    static class Postgresql {
        @Bean ComplianceApplicationService complianceApplicationService(DataSource dataSource,TransactionalEventStore eventStore,
                PlatformCapabilityService capabilities,PartnerApplicationService partners,RsotQueryService rsot,
                @Qualifier("platformClock") Clock clock,@Qualifier("correlationIdentifiers") UuidV7Generator identifiers,
                @Value("${infranexum.itam.compliance-alert-thresholds:180,120,90,60,30,15,7,1}") String alertThresholds){
            return service(dataSource,eventStore,capabilities,partners,rsot,clock,identifiers,JdbcDatabaseDialect.POSTGRESQL,alertThresholds);
        }
    }
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="infranexum.persistence.mode",havingValue="ORACLE")
    static class Oracle {
        @Bean ComplianceApplicationService complianceApplicationService(DataSource dataSource,TransactionalEventStore eventStore,
                PlatformCapabilityService capabilities,PartnerApplicationService partners,RsotQueryService rsot,
                @Qualifier("platformClock") Clock clock,@Qualifier("correlationIdentifiers") UuidV7Generator identifiers,
                @Value("${infranexum.itam.compliance-alert-thresholds:180,120,90,60,30,15,7,1}") String alertThresholds){
            return service(dataSource,eventStore,capabilities,partners,rsot,clock,identifiers,JdbcDatabaseDialect.ORACLE,alertThresholds);
        }
    }

    @Bean @ConditionalOnBean(ComplianceApplicationService.class)
    ItamComplianceAlertScheduler itamComplianceAlertScheduler(ComplianceApplicationService compliance,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers,@Qualifier("platformClock") Clock clock){
        return new ItamComplianceAlertScheduler(compliance,identifiers,clock);
    }

    @Bean @ConditionalOnBean({ComplianceApplicationService.class,AssetApplicationService.class})
    ItamComplianceCli itamComplianceCli(LocalAuthenticationService authentication,RbacAuthorizationService authorization,
            PolicyDecisionService policyDecisions,IdentityAccessFeaturePolicy features,PlatformCapabilityService capabilities,
            ComplianceApplicationService compliance,AssetApplicationService assets,
            @Qualifier("correlationIdentifiers") UuidV7Generator identifiers,@Qualifier("platformClock") Clock clock){
        return new ItamComplianceCli(authentication,authorization,policyDecisions,features,capabilities,compliance,assets,identifiers,clock);
    }

    private static ComplianceApplicationService service(DataSource dataSource,TransactionalEventStore eventStore,
            PlatformCapabilityService capabilities,PartnerApplicationService partners,RsotQueryService rsot,
            @Qualifier("platformClock") Clock clock,UuidV7Generator identifiers,JdbcDatabaseDialect dialect,String alertThresholds){
        if(!(eventStore instanceof JdbcTransactionalEventStore jdbcEvents))throw new IllegalStateException("ITAM compliance requires durable JDBC transactional events");
        JdbcComplianceRepository repository=new JdbcComplianceRepository(dataSource,jdbcEvents,dialect);
        return new ComplianceApplicationService(new JdbcAssetRepository(dataSource,jdbcEvents,dialect),repository,
                new JdbcComplianceIdempotencyRepository(jdbcEvents,dialect),
                new ItamComplianceReferencePolicy(partners,new JdbcSubdivisionRepository(dataSource,jdbcEvents,dialect),rsot,repository),
                featurePolicy(capabilities),eventStore,identifiers,clock,parseAlertThresholds(alertThresholds));
    }

    static ComplianceFeaturePolicy featurePolicy(PlatformCapabilityService capabilities){return new ComplianceFeaturePolicy(){
        @Override public boolean complianceEnabled(){return capabilities.explain("itam.partners").available()
                && capabilities.explain("itam.assets").available() && capabilities.explain("itam.compliance").available();}
        @Override public long contractLimit(){return capabilities.quotaPlan().limit("itam.contracts.max");}
    };}

    static int[] parseAlertThresholds(String value){
        if(value==null||value.isBlank())throw new IllegalArgumentException("ITAM compliance alert thresholds must not be empty");
        String[] parts=value.split(",",-1);int[] parsed=new int[parts.length];
        for(int index=0;index<parts.length;index++){try{parsed[index]=Integer.parseInt(parts[index].strip());}catch(NumberFormatException failure){throw new IllegalArgumentException("invalid ITAM compliance alert threshold: "+parts[index],failure);}}
        return parsed;
    }
}
