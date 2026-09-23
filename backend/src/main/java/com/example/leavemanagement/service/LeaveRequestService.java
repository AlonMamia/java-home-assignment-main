package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.exception.EmployeeNotFoundException;
import com.example.leavemanagement.exception.InsufficientVacationBalanceException;
import com.example.leavemanagement.exception.InvalidLeaveRequestStateException;
import com.example.leavemanagement.exception.LeaveRequestNotFoundException;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.temporal.ChronoUnit;

// Business logic for leave requests. create() still maps its own business exceptions to
// a ResponseEntity locally. approve() does not: it is @Transactional, and catching an
// exception inside a @Transactional method stops it from escaping, which stops Spring's
// transaction interceptor from rolling back the transaction because of it - if a later
// change added a write before the exception is thrown, that write would be committed
// despite the request being rejected. So approve() lets its business exceptions propagate
// to the caller; LeaveRequestsController lets them propagate further, and
// GlobalExceptionHandler (@RestControllerAdvice) maps them to the right HTTP status.
@Service
public class LeaveRequestService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeRepository employeeRepository;

    public LeaveRequestService(LeaveRequestRepository leaveRequestRepository,
                                EmployeeRepository employeeRepository) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.employeeRepository = employeeRepository;
    }

    public ResponseEntity<?> create(CreateLeaveRequestDto dto) {
        try {
            return ResponseEntity.ok(createInternal(dto));
        } catch (EmployeeNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (InsufficientVacationBalanceException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private LeaveRequest createInternal(CreateLeaveRequestDto dto) {
        Employee employee = employeeRepository.findById(dto.getEmployeeId())
                .orElseThrow(() -> new EmployeeNotFoundException("Employee not found"));

        int days = (int) ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate()) + 1;

        // How many vacation days has the employee already used this year?
        int used = leaveRequestRepository
                .findByEmployeeIdAndTypeAndStatus(dto.getEmployeeId(), LeaveType.VACATION, LeaveStatus.APPROVED)
                .stream()
                .mapToInt(LeaveRequest::getDays)
                .sum();

        // Make sure the request does not exceed the remaining quota (already-used days + this request).
        if (dto.getType() == LeaveType.VACATION && used + days > employee.getAnnualQuota()) {
            throw new InsufficientVacationBalanceException("Not enough vacation balance");
        }

        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(dto.getEmployeeId());
        request.setType(dto.getType());
        request.setStartDate(dto.getStartDate());
        request.setEndDate(dto.getEndDate());
        request.setDays(days);
        request.setStatus(LeaveStatus.PENDING);

        return leaveRequestRepository.save(request);
    }

    // Locks the request row first (so a second concurrent approve() of the same id
    // blocks and then sees the fresh, already-approved status), then locks the
    // employee row (so two different pending requests for the same employee can't
    // both pass the balance check at once). Both locks are released together when
    // this transaction commits or rolls back. See DECISIONS.md for the trade-offs.
    //
    // Business exceptions thrown below are intentionally left uncaught so they escape
    // this @Transactional method and reach GlobalExceptionHandler.
    @Transactional
    public LeaveRequest approve(Long leaveRequestId) {
        LeaveRequest request = leaveRequestRepository.findByIdForUpdate(leaveRequestId)
                .orElseThrow(() -> new LeaveRequestNotFoundException(
                        "Leave request " + leaveRequestId + " not found"));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new InvalidLeaveRequestStateException(
                    "Leave request " + leaveRequestId + " is already " + request.getStatus());
        }

        Employee employee = employeeRepository.findByIdForUpdate(request.getEmployeeId())
                .orElseThrow(() -> new EmployeeNotFoundException(
                        "Employee " + request.getEmployeeId() + " not found"));

        if (request.getType() == LeaveType.VACATION) {
            int used = leaveRequestRepository
                    .findByEmployeeIdAndTypeAndStatus(employee.getId(), LeaveType.VACATION, LeaveStatus.APPROVED)
                    .stream()
                    .mapToInt(LeaveRequest::getDays)
                    .sum();

            if (used + request.getDays() > employee.getAnnualQuota()) {
                throw new InsufficientVacationBalanceException(
                        "Approving leave request " + leaveRequestId + " would exceed the annual vacation quota");
            }
        }

        request.setStatus(LeaveStatus.APPROVED);
        return leaveRequestRepository.save(request);
    }
}
