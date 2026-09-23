package com.example.leavemanagement.exception;

// Thrown when an operation is attempted on a leave request that is not in the
// required status (e.g. approving one that's already approved or rejected).
public class InvalidLeaveRequestStateException extends RuntimeException {
    public InvalidLeaveRequestStateException(String message) {
        super(message);
    }
}
