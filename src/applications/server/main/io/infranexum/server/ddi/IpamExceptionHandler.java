package io.infranexum.server.ddi;

import io.infranexum.ddi.ipam.domain.IpamConflictException;
import io.infranexum.ddi.ipam.domain.IpamNotFoundException;
import io.infranexum.server.http.ApiProblem;
import io.infranexum.server.http.ApiProblemSupport;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Canonical RFC 9457 translation for DDI/IPAM failures. */
@RestControllerAdvice(assignableTypes = IpamController.class)
final class IpamExceptionHandler {
    private final ApiProblemSupport problems;

    IpamExceptionHandler(ApiProblemSupport problems) {
        this.problems = Objects.requireNonNull(problems, "problems");
    }

    @ExceptionHandler(IpamNotFoundException.class)
    ResponseEntity<ApiProblem> missing(IpamNotFoundException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "DDI_IPAM_NOT_FOUND", failure.getMessage(), request);
    }

    @ExceptionHandler(IpamConflictException.class)
    ResponseEntity<ApiProblem> conflict(IpamConflictException failure, HttpServletRequest request) {
        HttpStatus status = failure.code().endsWith("CAPABILITY_UNAVAILABLE")
                ? HttpStatus.FORBIDDEN
                : HttpStatus.CONFLICT;
        return problem(status, failure.code(), failure.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiProblem> invalid(IllegalArgumentException failure, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "DDI_IPAM_INVALID_REQUEST", failure.getMessage(), request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        return problems.response(status, code, "DDI/IPAM request failed", detail, Map.of(), Map.of(), request);
    }
}
