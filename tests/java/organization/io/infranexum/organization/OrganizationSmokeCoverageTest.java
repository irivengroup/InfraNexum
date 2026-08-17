package io.infranexum.organization;

import org.junit.jupiter.api.Test;

/** Runs the complete organization smoke contract under Surefire/JaCoCo. */
final class OrganizationSmokeCoverageTest {
    @Test
    void organizationFoundationSmokeRunsUnderCoverage() {
        OrganizationFoundationSmoke.main(new String[0]);
    }
}
