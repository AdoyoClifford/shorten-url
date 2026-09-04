package org.adoyo.shortenurl.api;

import org.adoyo.shortenurl.service.CodeTakenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Plain JSON errors (docs/api-design.md 3). */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CodeTakenException.class)
    ResponseEntity<ErrorResponse> codeTaken(CodeTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ex) {
        // Malformed JSON, or a field that will not parse into its type. The exception's own
        // message quotes the offending input, so it is not safe to echo back.
        return ResponseEntity.badRequest().body(new ErrorResponse("request body is not valid JSON"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> exhausted(IllegalStateException ex) {
        // Running out of code attempts is ours, not the caller's.
        log.error("Could not satisfy request", ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse("could not create the link, please retry"));
    }
}
