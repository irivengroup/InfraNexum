package io.infranexum.server.itam.cli;

import io.infranexum.server.InfraNexumServerApplication;
import java.io.PrintWriter;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Dedicated non-Web entry point for the Server-owned ITAM Partner CLI. */
public final class ItamPartnerCliApplication {
    private ItamPartnerCliApplication() {}

    public static void main(String[] args) {
        int delimiter = indexOf(args, "--");
        if (delimiter < 0) {
            System.err.println("usage: java ... ItamPartnerCliApplication [Spring options] -- itam partner ...");
            System.exit(ItamPartnerCli.EXIT_USAGE); return;
        }
        String[] bootArgs = Arrays.copyOfRange(args, 0, delimiter);
        String[] cliArgs = Arrays.copyOfRange(args, delimiter + 1, args.length);
        int exit;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(InfraNexumServerApplication.class)
                .web(WebApplicationType.NONE).properties("spring.main.banner-mode=off").run(bootArgs)) {
            exit = context.getBean(ItamPartnerCli.class).run(cliArgs, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        }
        System.exit(exit);
    }

    private static int indexOf(String[] values, String target) {
        for (int index = 0; index < values.length; index++) if (target.equals(values[index])) return index;
        return -1;
    }
}
