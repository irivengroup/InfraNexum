package io.infranexum.server.itam;

import io.infranexum.core.contracts.UuidV7Generator;
import io.infranexum.itam.compliance.application.ComplianceApplicationService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;

/** Multi-node-safe contractual deadline publisher; database deduplication prevents duplicate threshold events. */
public final class ItamComplianceAlertScheduler {
    private final ComplianceApplicationService compliance;
    private final UuidV7Generator identifiers;
    private final Clock clock;

    public ItamComplianceAlertScheduler(ComplianceApplicationService compliance,UuidV7Generator identifiers,@Qualifier("platformClock") Clock clock){
        this.compliance=Objects.requireNonNull(compliance,"compliance");this.identifiers=Objects.requireNonNull(identifiers,"identifiers");this.clock=Objects.requireNonNull(clock,"clock");
    }

    @Scheduled(fixedDelayString="${infranexum.itam.compliance-alert-interval:PT1H}")
    public void publishDueAlerts(){if(compliance.enabled())compliance.publishDueAlerts(LocalDate.now(clock),identifiers.next());}
}
