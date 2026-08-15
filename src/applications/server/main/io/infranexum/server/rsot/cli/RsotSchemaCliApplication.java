package io.infranexum.server.rsot.cli;

import io.infranexum.server.InfraNexumServerApplication;
import java.io.PrintWriter;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Dedicated non-Web entry point for the Server-owned RSOT Schema Registry CLI. */
public final class RsotSchemaCliApplication {
    private RsotSchemaCliApplication() {}

    public static void main(String[] args) {
        int delimiter = indexOf(args, "--");
        if (delimiter < 0) {
            System.err.println("usage: java ... RsotSchemaCliApplication [Spring options] -- rsot ...");
            System.exit(RsotSchemaCli.EXIT_USAGE);
            return;
        }
        String[] bootArgs = Arrays.copyOfRange(args, 0, delimiter);
        String[] cliArgs = Arrays.copyOfRange(args, delimiter + 1, args.length);
        int exit;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(InfraNexumServerApplication.class)
                .web(WebApplicationType.NONE).properties("spring.main.banner-mode=off").run(bootArgs)) {
            exit = context.getBean(RsotSchemaCli.class).run(cliArgs, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        }
        System.exit(exit);
    }

    private static int indexOf(String[] values, String target) {
        for (int index = 0; index < values.length; index++) if (target.equals(values[index])) return index;
        return -1;
    }
}
