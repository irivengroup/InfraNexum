package io.infranexum.server.rsot;

import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.adapters.persistence.jdbc.JdbcRsotRepository;
import io.infranexum.rsot.application.RsotAuthorityService;
import io.infranexum.rsot.application.RsotQueryService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composes the read-only PGM-06-E01 RSOT foundation for supported durable databases. */
@Configuration(proxyBeanMethods = false)
public class RsotRuntimeConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "POSTGRESQL")
    static class Postgresql {
        @Bean
        JdbcRsotRepository rsotRepository(DataSource dataSource) {
            return new JdbcRsotRepository(dataSource, JdbcDatabaseDialect.POSTGRESQL);
        }

        @Bean
        RsotAuthorityService rsotAuthorityService(
                JdbcRsotRepository repository, @Qualifier("platformClock") Clock clock) {
            return new RsotAuthorityService(repository, clock);
        }

        @Bean
        RsotQueryService rsotQueryService(JdbcRsotRepository repository) {
            return new RsotQueryService(repository);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "infranexum.persistence.mode", havingValue = "ORACLE")
    static class Oracle {
        @Bean
        JdbcRsotRepository rsotRepository(DataSource dataSource) {
            return new JdbcRsotRepository(dataSource, JdbcDatabaseDialect.ORACLE);
        }

        @Bean
        RsotAuthorityService rsotAuthorityService(
                JdbcRsotRepository repository, @Qualifier("platformClock") Clock clock) {
            return new RsotAuthorityService(repository, clock);
        }

        @Bean
        RsotQueryService rsotQueryService(JdbcRsotRepository repository) {
            return new RsotQueryService(repository);
        }
    }
}
