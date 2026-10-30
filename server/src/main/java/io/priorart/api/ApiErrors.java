package io.priorart.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** The extraction model endpoint failed or is unreachable: a gateway problem, not a server bug. */
    @ExceptionHandler(org.springframework.web.client.RestClientException.class)
    ResponseEntity<Map<String, String>> modelUnavailable(org.springframework.web.client.RestClientException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "extraction model unavailable: " + e.getMostSpecificCause().getMessage()));
    }

    @ExceptionHandler(io.priorart.auth.Unauthorized.class)
    ResponseEntity<Map<String, String>> unauthorized(io.priorart.auth.Unauthorized e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(io.priorart.auth.Identity.ClaimCodeRequired.class)
    ResponseEntity<Map<String, String>> claimCode(io.priorart.auth.Identity.ClaimCodeRequired e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(io.priorart.auth.Identity.Conflict.class)
    ResponseEntity<Map<String, String>> claimed(io.priorart.auth.Identity.Conflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(io.priorart.auth.Forbidden.class)
    ResponseEntity<Map<String, String>> notAllowed(io.priorart.auth.Forbidden e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(io.priorart.api.DirectoryController.Forbidden.class)
    ResponseEntity<Map<String, String>> forbidden(io.priorart.api.DirectoryController.Forbidden e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        String msg = e.getMostSpecificCause().getMessage();
        if (msg != null && msg.contains("Detail:")) msg = msg.substring(msg.indexOf("Detail:") + 7).trim();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "already exists: " + msg));
    }
}
