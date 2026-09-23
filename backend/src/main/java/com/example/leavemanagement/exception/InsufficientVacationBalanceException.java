package com.example.leavemanagement.exception;

public class InsufficientVacationBalanceException extends RuntimeException {
    public InsufficientVacationBalanceException(String message) {
        super(message);
    }
}
