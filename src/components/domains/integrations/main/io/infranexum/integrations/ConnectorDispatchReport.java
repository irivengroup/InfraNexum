package io.infranexum.integrations;

/** Bounded result of one connector inbox dispatcher iteration. */
public record ConnectorDispatchReport(int claimed,int processed,int retried,int deadLettered){
    public ConnectorDispatchReport{if(claimed<0||processed<0||retried<0||deadLettered<0)throw new IllegalArgumentException("dispatch counters must be non-negative");if(processed+retried+deadLettered!=claimed)throw new IllegalArgumentException("dispatch counters must reconcile with claimed");}
}
