package com.example.leavemanagement;

import com.example.leavemanagement.dto.LeaveRequestDtoIn;
import com.example.leavemanagement.exception.EmployeeNotFoundException;
import com.example.leavemanagement.exception.InsufficientVacationBalanceException;
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

import static org.junit.jupiter.api.Assertions.*;

// Runs against a real, throwaway PostgreSQL started by Testcontainers.
// (Docker must be available on the machine running the tests.)
@SpringBootTest
@Testcontainers
class LeaveRequestsTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Business logic lives in the service layer, which now returns the created
    // LeaveRequest directly and throws typed exceptions on failure (HTTP-status mapping
    // is covered separately in LeaveRequestsControllerCreateHttpTests), so these tests
    // exercise LeaveRequestService directly rather than the controller.
    @Autowired
    private LeaveRequestService leaveRequestService;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private LeaveRequestRepository leaveRequests;

    @Test
    void create_WithinQuota_Succeeds() {
        // Arrange
        Employee emp = new Employee();
        emp.setName("Test Emp");
        emp.setAnnualQuota(20);
        employees.save(emp);

        long before = leaveRequests.count();

        LeaveRequestDtoIn dto = new LeaveRequestDtoIn();
        dto.setEmployeeId(emp.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 3)); // 3 days, well within the quota

        // Act
        LeaveRequest result = leaveRequestService.create(dto);

        // Assert
        assertNotNull(result.getId());
        assertEquals(before + 1, leaveRequests.count());
    }

    @Test
    void create_ExceedingRemainingBalance_IsRejected() {
        // Arrange: employee has a 10-day quota and has already used 8 approved days.
        Employee emp = new Employee();
        emp.setName("Test Emp 2");
        emp.setAnnualQuota(10);
        employees.save(emp);

        LeaveRequest alreadyApproved = new LeaveRequest();
        alreadyApproved.setEmployeeId(emp.getId());
        alreadyApproved.setType(LeaveType.VACATION);
        alreadyApproved.setStartDate(LocalDate.of(2026, 1, 5));
        alreadyApproved.setEndDate(LocalDate.of(2026, 1, 12)); // 8 days
        alreadyApproved.setDays(8);
        alreadyApproved.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(alreadyApproved);

        long before = leaveRequests.count();

        // Act: request 5 more days, which would push the employee to 13/10 days.
        LeaveRequestDtoIn dto = new LeaveRequestDtoIn();
        dto.setEmployeeId(emp.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 5)); // 5 days

        // Assert: rejected, and no new request was persisted.
        assertThrows(InsufficientVacationBalanceException.class, () -> leaveRequestService.create(dto));
        assertEquals(before, leaveRequests.count());
    }

    @Test
    void create_ExactlyAtRemainingBalance_Succeeds() {
        // Arrange: employee has a 10-day quota and has already used 8 approved days,
        // leaving exactly 2 days of remaining balance.
        Employee emp = new Employee();
        emp.setName("Test Emp 3");
        emp.setAnnualQuota(10);
        employees.save(emp);

        LeaveRequest alreadyApproved = new LeaveRequest();
        alreadyApproved.setEmployeeId(emp.getId());
        alreadyApproved.setType(LeaveType.VACATION);
        alreadyApproved.setStartDate(LocalDate.of(2026, 1, 5));
        alreadyApproved.setEndDate(LocalDate.of(2026, 1, 12)); // 8 days
        alreadyApproved.setDays(8);
        alreadyApproved.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(alreadyApproved);

        long before = leaveRequests.count();

        // Act: request exactly the 2 remaining days (8 + 2 = 10, the full quota).
        LeaveRequestDtoIn dto = new LeaveRequestDtoIn();
        dto.setEmployeeId(emp.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 2)); // 2 days

        LeaveRequest result = leaveRequestService.create(dto);

        // Assert: accepted, and the new request was persisted.
        assertNotNull(result.getId());
        assertEquals(before + 1, leaveRequests.count());
    }

    @Test
    void create_NonExistentEmployee_ReturnsNotFound() {
        LeaveRequestDtoIn dto = new LeaveRequestDtoIn();
        dto.setEmployeeId(999_999L);
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 2));

        assertThrows(EmployeeNotFoundException.class, () -> leaveRequestService.create(dto));
    }
}
