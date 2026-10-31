package ai.infrrd.loansearch.api;

import ai.infrrd.loansearch.search.QuerySyntaxException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/** Maps expected API failures to a consistent, client-safe error contract. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(QuerySyntaxException.class)
    public ResponseEntity<ApiError> badQuery(QuerySyntaxException exception) {
        return error(HttpStatus.BAD_REQUEST, "Invalid search query", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> unavailable(IllegalStateException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Package unavailable", exception.getMessage());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String title, String message) {
        return ResponseEntity.status(status).body(new ApiError(
                Instant.now(), status.value(), title, message == null ? title : message));
    }

    public record ApiError(Instant timestamp, int status, String error, String message) { }
}
