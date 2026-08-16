package io.infranexum.server.itam;

import io.infranexum.core.events.TransactionExecutionException;
import io.infranexum.identity.access.domain.IdentityAccessException;
import io.infranexum.itam.asset.domain.AssetConflictException;
import io.infranexum.itam.asset.domain.AssetNotFoundException;
import io.infranexum.itam.asset.domain.AssetQuotaException;
import io.infranexum.server.http.ApiProblem;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 translation for ITAM Asset failures without persistence or policy leakage. */
@Order(Ordered.HIGHEST_PRECEDENCE + 51)
@RestControllerAdvice(assignableTypes = ItamAssetController.class)
public final class ItamAssetExceptionHandler {
    private final ApiProblemSupport problems;

    public ItamAssetExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(AssetNotFoundException.class)
    ResponseEntity<ApiProblem> notFound(AssetNotFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "ITAM_ASSET_NOT_FOUND", failure.getMessage(), request);
    }

    @ExceptionHandler(AssetQuotaException.class)
    ResponseEntity<ApiProblem> quota(AssetQuotaException failure, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "ITAM_ASSET_QUOTA_EXCEEDED", failure.getMessage(), request);
    }

    @ExceptionHandler(AssetConflictException.class)
    ResponseEntity<ApiProblem> conflict(AssetConflictException failure, HttpServletRequest request) {
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
    ResponseEntity<ApiProblem> authorization(IdentityAccessException failure, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, failure.code(), "authorization denied", request);
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiProblem> invalid(Exception failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "ITAM_ASSET_INVALID_REQUEST", failure.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> invalidBody(MethodArgumentNotValidException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "ITAM_ASSET_INVALID_REQUEST", "request validation failed", request);
    }

    @ExceptionHandler(TransactionExecutionException.class)
    ResponseEntity<ApiProblem> transaction(TransactionExecutionException failure, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "ITAM_ASSET_TRANSACTION_FAILED", "Asset transaction failed", request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return problems.response(status, code, "ITAM Asset request failed", detail, Map.of(), Map.of(), request);
    }
}
