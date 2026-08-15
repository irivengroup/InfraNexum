package io.infranexum.server.itam;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetNotFoundException;
import io.infranexum.itam.asset.domain.AssetQuotaException;
import io.infranexum.server.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 translation for ITAM Asset failures without persistence or policy leakage. */
@Order(Ordered.HIGHEST_PRECEDENCE + 51)
@RestControllerAdvice(assignableTypes = ItamAssetController.class)
public final class ItamAssetExceptionHandler {
    private final Clock clock;

    public ItamAssetExceptionHandler(@Qualifier("platformClock") Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @ExceptionHandler(AssetNotFoundException.class)
    ResponseEntity<Problem> notFound(AssetNotFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "ITAM_ASSET_NOT_FOUND", failure.getMessage(), request);
    }

    @ExceptionHandler(AssetQuotaException.class)
    ResponseEntity<Problem> quota(AssetQuotaException failure, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "ITAM_ASSET_QUOTA_EXCEEDED", failure.getMessage(), request);
    }

    @ExceptionHandler(AssetConflictException.class)
    ResponseEntity<Problem> conflict(AssetConflictException failure, HttpServletRequest request) {
        HttpStatus status = switch (failure.code()) {
            case "ITAM_ASSET_CAPABILITY_UNAVAILABLE", "ITAM_ASSET_COMPLIANCE_GATE_UNAVAILABLE" -> HttpStatus.FORBIDDEN;
            case "ITAM_ASSET_ORGANIZATION_INVALID", "ITAM_ASSET_SUBDIVISION_INVALID", "ITAM_ASSET_RSOT_SCOPE_MISMATCH",
                    "ITAM_ASSET_PARTNER_SCOPE_MISMATCH", "ITAM_ASSET_PARTNER_NOT_SELECTABLE",
                    "ITAM_ASSET_ACQUISITION_PARTNER_INVALID", "ITAM_ASSET_MAINTENANCE_PARTNER_INVALID",
                    "ITAM_ASSET_CUSTODIAN_SCOPE_MISMATCH", "ITAM_ASSET_CUSTODIAN_INVALID" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, failure.code(), failure.getMessage(), request);
    }

    @ExceptionHandler(IdentityAccessException.class)
    ResponseEntity<Problem> authorization(IdentityAccessException failure, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, failure.code(), "authorization denied", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<Problem> invalid(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "ITAM_ASSET_INVALID_REQUEST", safe(failure.getMessage()), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "ITAM_ASSET_INVALID_REQUEST", "request validation failed", request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<Problem> transaction(TransactionExecutionException failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "ITAM_ASSET_TRANSACTION_FAILED", "Asset transaction failed", request);
    }

    private ResponseEntity<Problem> problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        Problem body = new Problem(
                URI.create("urn:infranexum:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')),
                "ITAM Asset request failed", status.value(), safe(detail), request.getRequestURI(), code,
                clock.instant(), CorrelationContext.traceId(request));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "request failed";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    record Problem(URI type, String title, int status, String detail, String instance, String code, Instant occurredAt, String traceId) {}
}
