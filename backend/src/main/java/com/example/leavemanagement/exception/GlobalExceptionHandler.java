package com.example.leavemanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Maps business exceptions that propagate out of a service/controller method to the
// corresponding HTTP response. Keeps that mapping in one place instead of duplicated
// try/catch blocks in each controller method.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({LeaveRequestNotFoundException.class, EmployeeNotFoundException.class})
    public ResponseEntity<String> handleNotFound(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }

    @ExceptionHandler(InvalidLeaveRequestStateException.class)
    public ResponseEntity<String> handleInvalidState(InvalidLeaveRequestStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(InsufficientVacationBalanceException.class)
    public ResponseEntity<String> handleInsufficientBalance(InsufficientVacationBalanceException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
