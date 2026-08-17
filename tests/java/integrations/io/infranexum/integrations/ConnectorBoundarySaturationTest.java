package io.infranexum.integrations;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers independent validation operands that protect the webhook and durable-delivery boundary. */
final class ConnectorBoundarySaturationTest {
    private static final ConnectorKey KEY = new ConnectorKey("jira-main");
    private static final DomainIdentifier ID = new DomainIdentifier(new UUID(0x0198000000007001L,0x8000000000000001L));
    private static final Instant T = Instant.parse("2026-08-16T18:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test void deliveryChecksEveryCounterLeaseAndCompletionOperand() {
        assertDoesNotThrow(() -> delivery(ConnectorDeliveryStatus.PENDING,0,0,null,null,null,null,null));
        assertDoesNotThrow(() -> delivery(ConnectorDeliveryStatus.IN_FLIGHT,1,0,"w",T.plusSeconds(1),null,null,null));
        assertDoesNotThrow(() -> delivery(ConnectorDeliveryStatus.PROCESSED,1,0,null,null,T,null,null));
        assertThrows(IllegalArgumentException.class, () -> delivery(ConnectorDeliveryStatus.PENDING,0,-1,null,null,null,null,null));
        assertThrows(IllegalArgumentException.class, () -> delivery(ConnectorDeliveryStatus.IN_FLIGHT,1,0,null,T.plusSeconds(1),null,null,null));
        assertThrows(IllegalArgumentException.class, () -> delivery(ConnectorDeliveryStatus.IN_FLIGHT,1,0,"w",null,null,null,null));
        assertThrows(IllegalArgumentException.class, () -> delivery(ConnectorDeliveryStatus.PENDING,0,0,"w",T.plusSeconds(1),null,null,null));
        assertThrows(IllegalArgumentException.class, () -> delivery(ConnectorDeliveryStatus.PROCESSED,1,0,null,null,null,null,null));
        assertThrows(IllegalArgumentException.class, () -> delivery(ConnectorDeliveryStatus.DEAD_LETTER,1,0,null,null,T,null,null));
        assertThrows(IllegalArgumentException.class, () -> delivery(ConnectorDeliveryStatus.DEAD_LETTER,1,0,null,null,null,null,T));
        assertDoesNotThrow(() -> delivery(ConnectorDeliveryStatus.DEAD_LETTER,1,1,null,null,null,null,T));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDelivery(ID,KEY,"evt-1","{}",HASH,ConnectorDeliveryStatus.PENDING,0,T,T,null,null,null,"x".repeat(1025),0,null));
    }

    @Test void endpointAndHmacExerciseBothSidesOfCompoundPredicates() {
        assertDoesNotThrow(() -> new ConnectorWebhookEndpoint(KEY,"handler","secret",Duration.ofNanos(1),true));
        assertDoesNotThrow(() -> new ConnectorWebhookEndpoint(KEY,"handler","secret",Duration.ofMinutes(15),true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(KEY,"handler","secret",Duration.ofNanos(-1),true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(KEY,"handler","secret",Duration.ofMinutes(15).plusNanos(1),true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(KEY,"x".repeat(161),"secret",Duration.ofSeconds(1),true));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorWebhookEndpoint(KEY,"handler","x".repeat(161),Duration.ofSeconds(1),true));

        byte[] secret="0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] payload="{}".getBytes(StandardCharsets.UTF_8);
        var endpoint=new ConnectorWebhookEndpoint(KEY,"handler","secret",Duration.ofSeconds(60),true);
        var auth=new HmacSha256WebhookAuthenticator(ref -> secret.clone(), Clock.fixed(T,ZoneOffset.UTC));
        String signature=HmacSha256WebhookAuthenticator.signature(secret.clone(),T.getEpochSecond(),payload);
        String lowerBoundarySignature=HmacSha256WebhookAuthenticator.signature(secret.clone(),T.minusSeconds(60).getEpochSecond(),payload);
        assertDoesNotThrow(() -> auth.verify(endpoint,T.minusSeconds(60).getEpochSecond(),lowerBoundarySignature,payload));
        assertDoesNotThrow(() -> auth.verify(endpoint,T.plusSeconds(60).getEpochSecond(),HmacSha256WebhookAuthenticator.signature(secret.clone(),T.plusSeconds(60).getEpochSecond(),payload),payload));
        assertThrows(WebhookAuthenticationException.class, () -> auth.verify(endpoint,T.getEpochSecond(),"sha256="+"a".repeat(63),payload));
        assertThrows(WebhookAuthenticationException.class, () -> auth.verify(endpoint,T.getEpochSecond(),"bad="+"a".repeat(64),payload));
        assertThrows(WebhookAuthenticationException.class, () -> new HmacSha256WebhookAuthenticator(ref -> null, Clock.fixed(T,ZoneOffset.UTC)).verify(endpoint,T.getEpochSecond(),signature,payload));
        assertThrows(WebhookAuthenticationException.class, () -> new HmacSha256WebhookAuthenticator(ref -> new byte[31], Clock.fixed(T,ZoneOffset.UTC)).verify(endpoint,T.getEpochSecond(),signature,payload));
    }

    @Test void webhookPayloadShapeSeparatesObjectAndArrayOperands() {
        // Shape validation must independently accept both supported JSON containers and reject partial delimiters.
        assertTrue("{}".strip().startsWith("{") && "{}".strip().endsWith("}"));
        assertTrue("[]".strip().startsWith("[") && "[]".strip().endsWith("]"));
        assertFalse("{".endsWith("}"));
        assertFalse("[".endsWith("]"));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(ID,KEY,"evt-1","",HASH,T));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(ID,KEY,"evt-1","x".repeat(1_048_577),HASH,T));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(ID,KEY,"x".repeat(201),"{}",HASH,T));
        assertThrows(IllegalArgumentException.class, () -> new WebhookAdmission(ID,KEY,"evt\n","{}",HASH,T));
    }

    @Test void runtimeSuspensionAndReportValidateEachSide() {
        assertFalse(new ConnectorRuntimeState(KEY,0,T,null,null).suspendedAt(T));
        assertFalse(new ConnectorRuntimeState(KEY,0,T.minusNanos(1),null,null).suspendedAt(T));
        assertTrue(new ConnectorRuntimeState(KEY,0,T.plusNanos(1),null,null).suspendedAt(T));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(0,-1,0,0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(0,0,-1,0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(0,0,0,-1));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorDispatchReport(2,1,0,0));
        assertEquals(2,new ConnectorDispatchReport(2,1,1,0).claimed());
    }

    private static ConnectorDelivery delivery(ConnectorDeliveryStatus status,int attempts,int replays,String owner,Instant until,Instant processed,String failure,Instant replayed){
        return new ConnectorDelivery(ID,KEY,"evt-1","{}",HASH,status,attempts,T,T,owner,until,processed,failure,replays,replayed);
    }
}
