package io.infranexum.server.http.idempotency;

import io.infranexum.adapters.persistence.jdbc.JdbcApiIdempotencyLedger;
import io.infranexum.adapters.persistence.jdbc.JdbcDatabaseDialect;
import io.infranexum.core.contracts.IdempotencyLedger;
import io.infranexum.server.http.ApiProblemSupport;
import io.infranexum.server.persistence.PersistenceMode;
import io.infranexum.server.persistence.PersistenceRuntimeProperties;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Runtime wiring for the PGM-05-E01 HTTP idempotency boundary. */
@Configuration(proxyBeanMethods = false)
public class ApiIdempotencyConfiguration {
    @Bean
    IdempotencyLedger apiIdempotencyLedger(DataSource dataSource, PersistenceRuntimeProperties persistence) {
        JdbcDatabaseDialect dialect = persistence.mode() == PersistenceMode.POSTGRESQL
                ? JdbcDatabaseDialect.POSTGRESQL : JdbcDatabaseDialect.ORACLE;
        return new JdbcApiIdempotencyLedger(dataSource, dialect);
    }

    @Bean
    ApiIdempotencyPolicy apiIdempotencyPolicy() { return new ApiIdempotencyPolicy(); }

    @Bean
    ApiIdempotencyFilter apiIdempotencyFilter(
            ApiIdempotencyPolicy policy,
            IdempotencyLedger ledger,
            ApiProblemSupport problems,
            @Qualifier("platformClock") Clock clock) {
        return new ApiIdempotencyFilter(policy, ledger, problems, clock);
    }
}
