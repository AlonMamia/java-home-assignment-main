package com.example.leavemanagement;

import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Exercises POST /api/leave-requests/{id}/approve through the real DispatcherServlet, so
// GlobalExceptionHandler (@RestControllerAdvice) is actually what maps the business
// exceptions thrown by LeaveRequestService.approve() to HTTP status codes - proving the
// status-code behavior (404 / 409 / 400) is preserved after moving that mapping out of
// the service and into the advice.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LeaveRequestsControllerApprovalHttpTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

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
    void approve_PendingRequest_ReturnsOk() throws Exception {
        Employee emp = newEmployee("Http Approve Emp", 20);
        LeaveRequest request = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3), 3);

        mockMvc.perform(post("/api/leave-requests/{id}/approve", request.getId()))
                .andExpect(status().isOk());
    }

    @Test
    void approve_AlreadyApprovedRequest_ReturnsConflict() throws Exception {
        Employee emp = newEmployee("Http Already Approved Emp", 20);
        LeaveRequest request = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3), 3);
        request.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(request);

        mockMvc.perform(post("/api/leave-requests/{id}/approve", request.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void approve_NonExistentId_ReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/leave-requests/{id}/approve", 999_999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void approve_ExceedingRemainingBalance_ReturnsBadRequest() throws Exception {
        Employee emp = newEmployee("Http Over Balance Emp", 5);
        LeaveRequest alreadyApproved = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 8), 4);
        alreadyApproved.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(alreadyApproved);

        LeaveRequest pending = newPendingVacationRequest(
                emp.getId(), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3), 3);

        mockMvc.perform(post("/api/leave-requests/{id}/approve", pending.getId()))
                .andExpect(status().isBadRequest());
    }
}
