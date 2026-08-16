package io.infranexum.integrations;

import io.infranexum.core.contracts.UuidV7Generator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

/** Authenticates, bounds and durably admits provider webhooks without executing connector code on the HTTP thread. */
public final class ConnectorWebhookService {
    private final ConnectorEndpointRegistry endpoints;
    private final HmacSha256WebhookAuthenticator authenticator;
    private final ConnectorInboxRepository inbox;
    private final ConnectorRuntimeObserver observer;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final int maximumPayloadBytes;

    public ConnectorWebhookService(ConnectorEndpointRegistry endpoints,HmacSha256WebhookAuthenticator authenticator,ConnectorInboxRepository inbox,ConnectorRuntimeObserver observer,UuidV7Generator ids,Clock clock,int maximumPayloadBytes){
        this.endpoints=Objects.requireNonNull(endpoints,"endpoints");this.authenticator=Objects.requireNonNull(authenticator,"authenticator");this.inbox=Objects.requireNonNull(inbox,"inbox");this.observer=Objects.requireNonNull(observer,"observer");this.ids=Objects.requireNonNull(ids,"ids");this.clock=Objects.requireNonNull(clock,"clock");
        if(maximumPayloadBytes<1||maximumPayloadBytes>1_048_576)throw new IllegalArgumentException("maximumPayloadBytes must be between 1 and 1048576");this.maximumPayloadBytes=maximumPayloadBytes;
    }

    public WebhookAdmissionOutcome admit(String connectorKey,String externalDeliveryId,long epochSecond,String signature,byte[] payload){
        ConnectorKey key=new ConnectorKey(connectorKey);Objects.requireNonNull(payload,"payload");
        ConnectorWebhookEndpoint endpoint=endpoints.find(key).filter(ConnectorWebhookEndpoint::enabled).orElseThrow(() -> new ConnectorEndpointUnavailableException("connector webhook endpoint is not available"));
        try{
            if(payload.length==0||payload.length>maximumPayloadBytes)throw new IllegalArgumentException("webhook payload size is invalid");
            String json=new String(payload,StandardCharsets.UTF_8);
            String shape=json.strip();
            if(!((shape.startsWith("{")&&shape.endsWith("}"))||(shape.startsWith("[")&&shape.endsWith("]"))))throw new IllegalArgumentException("webhook payload must be a JSON object or array");
            authenticator.verify(endpoint,epochSecond,signature,payload);
            var admission=new WebhookAdmission(ids.next(),key,externalDeliveryId,json,sha256(payload),clock.instant());
            WebhookAdmissionOutcome outcome=inbox.admit(admission);observer.admitted(key,outcome.duplicate());return outcome;
        }catch(RuntimeException failure){observer.rejected(key,failure.getClass().getSimpleName());throw failure;}
    }

    private static String sha256(byte[] payload){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 is unavailable",impossible);}}
}
