package io.infranexum.server;

import io.infranexum.server.configuration.ServerRuntimeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.modulith.Modulith;

/** Composition root for the InfraNexum Server application. */
@Modulith
@EnableConfigurationProperties(ServerRuntimeProperties.class)
public class InfraNexumServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(InfraNexumServerApplication.class, args);
    }
}
