package io.infranexum.server.identityaccess.cli;

import io.infranexum.server.InfraNexumServerApplication;
import java.io.PrintWriter;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Dedicated non-Web entry point for the Server-owned InfraNexum IAM CLI. */
public final class IdentityAccessCliApplication {
    private IdentityAccessCliApplication() {}

    public static void main(String[] args) {
        int delimiter = indexOf(args, "--");
        if (delimiter < 0) {
            System.err.println("usage: java ... IdentityAccessCliApplication [Spring options] -- iam ...");
            System.exit(IdentityAccessCli.EXIT_USAGE);
            return;
        }
        String[] bootArgs = Arrays.copyOfRange(args, 0, delimiter);
        String[] cliArgs = Arrays.copyOfRange(args, delimiter + 1, args.length);
        int exit;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(InfraNexumServerApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run(bootArgs)) {
            IdentityAccessCli cli = context.getBean(IdentityAccessCli.class);
            exit = cli.run(cliArgs, new PrintWriter(System.out, true), new PrintWriter(System.err, true));
        }
        System.exit(exit);
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) if (target.equals(values[i])) return i;
        return -1;
    }
}
