package com.example.leavemanagement;

import com.example.leavemanagement.exception.InsufficientVacationBalanceException;
import com.example.leavemanagement.exception.InvalidLeaveRequestStateException;
import com.example.leavemanagement.exception.LeaveRequestNotFoundException;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import com.example.leavemanagement.service.LeaveRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// Runs against a real, throwaway PostgreSQL started by Testcontainers.
// (Docker must be available on the machine running the tests.)
@SpringBootTest
@Testcontainers
class LeaveRequestApprovalTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // approve() now throws typed business exceptions instead of returning a
    // ResponseEntity - these tests exercise LeaveRequestService directly and assert on
    // the thrown exceptions. HTTP-status mapping (via GlobalExceptionHandler) is covered
    // separately in LeaveRequestsControllerApprovalHttpTests.
    @Autowired
    private LeaveRequestService leaveRequestService;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private LeaveRequestRepository leaveRequests;

    private Employee newEmployee(String name, int annualQuota) {
        Employee emp = new Employee();
        emp.setName(name);
        emp.setAnnualQuota(annualQuota);
        return employees.save(emp);
    }

    private LeaveRequest newPendingVacationRequest(Long employeeId, LocalDate start, LocalDate end, int days) {
        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(employeeId);
        request.setType(LeaveType.VACATION);
        request.setStartDate(start);
        request.setEndDate(end);
        request.setDays(days);
        request.setStatus(LeaveStatus.PENDING);
        return leaveRequests.save(request);
    }

    @Test
    void approve_PendingRequest_SucceedsAndUpdatesStatus() {
        Employee emp = newEmployee("Approve Emp", 20);
        LeaveRequest request = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3), 3);

        LeaveRequest approved = leaveRequestService.approve(request.getId());

        assertEquals(LeaveStatus.APPROVED, approved.getStatus());
        LeaveRequest reloaded = leaveRequests.findById(request.getId()).orElseThrow();
        assertEquals(LeaveStatus.APPROVED, reloaded.getStatus());
    }

    @Test
    void approve_AlreadyApprovedRequest_ThrowsInvalidState() {
        Employee emp = newEmployee("Already Approved Emp", 20);
        LeaveRequest request = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3), 3);
        request.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(request);

        assertThrows(InvalidLeaveRequestStateException.class,
                () -> leaveRequestService.approve(request.getId()));
    }

    @Test
    void approve_RejectedRequest_ThrowsInvalidState() {
        Employee emp = newEmployee("Rejected Emp", 20);
        LeaveRequest request = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3), 3);
        request.setStatus(LeaveStatus.REJECTED);
        leaveRequests.save(request);

        assertThrows(InvalidLeaveRequestStateException.class,
                () -> leaveRequestService.approve(request.getId()));
    }

    @Test
    void approve_NonExistentId_ThrowsNotFound() {
        assertThrows(LeaveRequestNotFoundException.class,
                () -> leaveRequestService.approve(999_999L));
    }

    @Test
    void approve_ExceedingRemainingBalance_ThrowsInsufficientBalance() {
        Employee emp = newEmployee("Over Balance Emp", 5);
        // Already-approved 4 days leaves only 1 day of remaining balance.
        LeaveRequest alreadyApproved = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 8), 4);
        alreadyApproved.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(alreadyApproved);

        LeaveRequest pending = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3), 3);

        assertThrows(InsufficientVacationBalanceException.class,
                () -> leaveRequestService.approve(pending.getId()));

        LeaveRequest reloaded = leaveRequests.findById(pending.getId()).orElseThrow();
        assertEquals(LeaveStatus.PENDING, reloaded.getStatus());
    }

    // Two pending requests (3 days each) for an employee with a 5-day quota: neither
    // exceeds the quota on its own, but approving both would (6 > 5). Approving them
    // concurrently must not let both succeed - proves the row-locking in
    // LeaveRequestService.approve() actually serializes the balance check.
    @Test
    void approve_ConcurrentApprovalsExceedingCombinedQuota_OnlyOneSucceeds() throws Exception {
        Employee emp = newEmployee("Concurrent Emp", 5);
        LeaveRequest requestA = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3), 3);
        LeaveRequest requestB = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3), 3);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<LeaveRequest> approveA = () -> {
            ready.countDown();
            start.await();
            return leaveRequestService.approve(requestA.getId());
        };
        Callable<LeaveRequest> approveB = () -> {
            ready.countDown();
            start.await();
            return leaveRequestService.approve(requestB.getId());
        };

        try {
            Future<LeaveRequest> futureA = pool.submit(approveA);
            Future<LeaveRequest> futureB = pool.submit(approveB);

            ready.await();
            start.countDown();

            int successCount = 0;
            int insufficientBalanceCount = 0;
            for (Future<LeaveRequest> future : List.of(futureA, futureB)) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successCount++;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof InsufficientVacationBalanceException) {
                        insufficientBalanceCount++;
                    } else {
                        throw e;
                    }
                }
            }

            assertEquals(1, successCount, "exactly one of the two concurrent approvals should succeed");
            assertEquals(1, insufficientBalanceCount, "the other approval should be rejected for exceeding the quota");

            int totalApprovedDays = leaveRequests
                    .findByEmployeeIdAndTypeAndStatus(emp.getId(), LeaveType.VACATION, LeaveStatus.APPROVED)
                    .stream()
                    .mapToInt(LeaveRequest::getDays)
                    .sum();
            assertTrue(totalApprovedDays <= emp.getAnnualQuota(),
                    "approved days must never exceed the employee's annual quota");
        } finally {
            pool.shutdownNow();
        }
    }
}
