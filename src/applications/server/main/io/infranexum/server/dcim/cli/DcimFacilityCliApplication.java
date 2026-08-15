package io.infranexum.server.dcim.cli;

import io.infranexum.server.InfraNexumServerApplication;
import java.io.PrintWriter;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Dedicated non-Web entry point for the Server-owned DCIM facility CLI. */
public final class DcimFacilityCliApplication {
    private DcimFacilityCliApplication() {}
    public static void main(String[] args) {
        int delimiter=indexOf(args,"--");if(delimiter<0){System.err.println("usage: java ... DcimFacilityCliApplication [Spring options] -- dcim site|building|floor|room|zone ...");System.exit(DcimFacilityCli.EXIT_USAGE);return;}
        String[] bootArgs=Arrays.copyOfRange(args,0,delimiter);String[] cliArgs=Arrays.copyOfRange(args,delimiter+1,args.length);int exit;
        try(ConfigurableApplicationContext context=new SpringApplicationBuilder(InfraNexumServerApplication.class).web(WebApplicationType.NONE).properties("spring.main.banner-mode=off").run(bootArgs)){
            exit=context.getBean(DcimFacilityCli.class).run(cliArgs,new PrintWriter(System.out,true),new PrintWriter(System.err,true));
        }
        System.exit(exit);
    }
    private static int indexOf(String[] values,String target){for(int i=0;i<values.length;i++)if(target.equals(values[i]))return i;return -1;}
}
