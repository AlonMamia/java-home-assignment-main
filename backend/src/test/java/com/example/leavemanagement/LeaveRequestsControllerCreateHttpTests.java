package com.example.leavemanagement;

import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Exercises POST /api/leave-requests through the real DispatcherServlet, so
// GlobalExceptionHandler (@RestControllerAdvice) is what maps the business exceptions
// thrown by LeaveRequestService.create() to HTTP status codes - proving the status-code
// behavior (200 / 404 / 400) is preserved after moving create() off its own local
// try/catch and onto the same exception-propagation pattern as approve().
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LeaveRequestsControllerCreateHttpTests {

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
    private ObjectMapper objectMapper;

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

    private String createRequestBody(Long employeeId, LocalDate start, LocalDate end) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "employeeId", employeeId,
                "type", LeaveType.VACATION.name(),
                "startDate", start.toString(),
                "endDate", end.toString()
        ));
    }

    @Test
    void create_WithinQuota_ReturnsOk() throws Exception {
        Employee emp = newEmployee("Http Create Emp", 20);

        // Asserts the response is LeaveRequestDtoOut's shape, not the LeaveRequest
        // entity's: same field names/values as before the DTO refactor, and no leaked
        // Hibernate-proxy internals. `employee` is null here because create() never
        // populates the entity's association - same as before the refactor.
        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody(emp.getId(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.employeeId").value(emp.getId()))
                .andExpect(jsonPath("$.employee").value(nullValue()))
                .andExpect(jsonPath("$.type").value(LeaveType.VACATION.ordinal()))
                .andExpect(jsonPath("$.status").value(LeaveStatus.PENDING.ordinal()))
                .andExpect(jsonPath("$.days").value(3))
                .andExpect(jsonPath("$.startDate").value("2026-03-01"))
                .andExpect(jsonPath("$.endDate").value("2026-03-03"));
    }

    @Test
    void create_NonExistentEmployee_ReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody(999_999L, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2))))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_ExceedingRemainingBalance_ReturnsBadRequest() throws Exception {
        Employee emp = newEmployee("Http Over Balance Create Emp", 10);

        LeaveRequest alreadyApproved = new LeaveRequest();
        alreadyApproved.setEmployeeId(emp.getId());
        alreadyApproved.setType(LeaveType.VACATION);
        alreadyApproved.setStartDate(LocalDate.of(2026, 1, 5));
        alreadyApproved.setEndDate(LocalDate.of(2026, 1, 12)); // 8 days
        alreadyApproved.setDays(8);
        alreadyApproved.setStatus(LeaveStatus.APPROVED);
        leaveRequests.save(alreadyApproved);

        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody(emp.getId(), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_MissingRequiredField_ReturnsBadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "type", LeaveType.VACATION.name(),
                "startDate", "2026-03-01",
                "endDate", "2026-03-02"
        ));

        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
