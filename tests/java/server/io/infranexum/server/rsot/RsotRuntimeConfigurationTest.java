package io.infranexum.server.rsot;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.infranexum.adapters.persistence.jdbc.JdbcRsotRepository;
import io.infranexum.rsot.application.RsotAuthorityService;
import io.infranexum.rsot.application.RsotQueryService;
import io.infranexum.server.persistence.UnavailableDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Composition-root coverage for both supported RSOT persistence modes. */
class RsotRuntimeConfigurationTest {
    private static final DataSource DATA_SOURCE = new UnavailableDataSource("test only");

    @Test
    void postgresqlConfigurationBuildsRepositoryAndUseCases() {
        Clock clock = Clock.systemUTC();
        RsotRuntimeConfiguration.Postgresql configuration = new RsotRuntimeConfiguration.Postgresql();
        JdbcRsotRepository repository = configuration.rsotRepository(DATA_SOURCE);
        assertNotNull(repository);
        assertInstanceOf(RsotAuthorityService.class, configuration.rsotAuthorityService(repository, clock));
        assertInstanceOf(RsotQueryService.class, configuration.rsotQueryService(repository));
    }

    @Test
    void oracleConfigurationBuildsRepositoryAndUseCases() {
        Clock clock = Clock.systemUTC();
        RsotRuntimeConfiguration.Oracle configuration = new RsotRuntimeConfiguration.Oracle();
        JdbcRsotRepository repository = configuration.rsotRepository(DATA_SOURCE);
        assertNotNull(repository);
        assertInstanceOf(RsotAuthorityService.class, configuration.rsotAuthorityService(repository, clock));
        assertInstanceOf(RsotQueryService.class, configuration.rsotQueryService(repository));
    }

    @Test
    void useCaseFactoriesRemainFailFastOnMissingRequiredDependencies() {
        JdbcRsotRepository repository = new RsotRuntimeConfiguration.Postgresql().rsotRepository(DATA_SOURCE);
        RsotRuntimeConfiguration.Postgresql configuration = new RsotRuntimeConfiguration.Postgresql();
        assertThrows(NullPointerException.class, () -> configuration.rsotAuthorityService(repository, null));
        assertThrows(NullPointerException.class, () -> configuration.rsotQueryService(null));
    }
}
