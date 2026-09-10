package com.changeguard.api;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class, jakarta.validation.ConstraintViolationException.class})
    public ProblemDetail validation(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request fields are missing or outside their allowed limits");
    }
    @ExceptionHandler(software.amazon.awssdk.core.exception.SdkException.class) public ProblemDetail aws(software.amazon.awssdk.core.exception.SdkException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "AWS review context is unavailable; retry later with the same client token");
    }
    @ExceptionHandler(IllegalArgumentException.class) public ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class}) public ProblemDetail database(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Review storage is temporarily unavailable; retry with the same idempotency key");
    }
}
