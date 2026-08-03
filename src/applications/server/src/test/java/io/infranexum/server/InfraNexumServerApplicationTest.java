package io.infranexum.server;

import org.junit.jupiter.api.Test;

class InfraNexumServerApplicationTest {
    @Test
    void startsThroughTheExecutableEntryPoint() {
        InfraNexumServerApplication.main(new String[] {
            "--spring.main.web-application-type=none",
            "--spring.main.banner-mode=off",
            "--spring.main.register-shutdown-hook=false"
        });
    }
}
