package io.infranexum.server.itam.cli;

import io.infranexum.server.InfraNexumServerApplication;
import java.io.PrintWriter;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Non-web Spring entry point for PGM-07-E03 contractual-governance CLI. */
public final class ItamComplianceCliApplication {
    private ItamComplianceCliApplication() {}
    public static void main(String[] args) {
        int separator=Arrays.asList(args).indexOf("--");
        if(separator<0||separator==args.length-1){System.err.println("usage: java ... ItamComplianceCliApplication [Spring options] -- itam warranty|license|support-coverage|support-authorization|warranty-type|compliance ...");System.exit(ItamComplianceCli.EXIT_USAGE);return;}
        String[] springArgs=Arrays.copyOfRange(args,0,separator);String[] cliArgs=Arrays.copyOfRange(args,separator+1,args.length);int exit;
        try(var context=new SpringApplicationBuilder(InfraNexumServerApplication.class).web(WebApplicationType.NONE).run(springArgs)){
            exit=context.getBean(ItamComplianceCli.class).run(cliArgs,new PrintWriter(System.out,true),new PrintWriter(System.err,true));
        }
        System.exit(exit);
    }
}
