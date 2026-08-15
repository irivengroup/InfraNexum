package io.infranexum.server.ddi.cli;

import io.infranexum.server.InfraNexumServerApplication;
import java.io.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Non-web Server entry point for DDI/IPAM automation. */
public final class IpamCliApplication{private IpamCliApplication(){}public static void main(String[] args){int d=index(args,"--");if(d<0){System.err.println("usage: java ... IpamCliApplication [Spring options] -- ddi ipam ...");System.exit(IpamCli.EXIT_USAGE);return;}try(ConfigurableApplicationContext c=new SpringApplicationBuilder(InfraNexumServerApplication.class).web(WebApplicationType.NONE).properties("spring.main.banner-mode=off").run(Arrays.copyOfRange(args,0,d))){System.exit(c.getBean(IpamCli.class).run(Arrays.copyOfRange(args,d+1,args.length),new PrintWriter(System.out,true),new PrintWriter(System.err,true)));}}private static int index(String[] a,String x){for(int i=0;i<a.length;i++)if(x.equals(a[i]))return i;return -1;}}
