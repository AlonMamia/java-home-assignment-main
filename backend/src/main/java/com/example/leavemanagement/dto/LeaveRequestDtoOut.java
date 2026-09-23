package com.example.leavemanagement.dto;

import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;

import java.time.LocalDate;

// Response shape for LeaveRequest. `DtoOut` suffix marks it as data returned to API
// clients (see DECISIONS.md). Mirrors the entity's previously-serialized fields exactly
// (id, employeeId, employee, type, startDate, endDate, status, days) so the JSON
// contract is unchanged, but as a plain POJO instead of the JPA entity. `employee` stays
// null exactly when the underlying entity's association wasn't loaded (e.g. the object
// returned by create(), which never had it populated) - same as before.
public class LeaveRequestDtoOut {

    private final Long id;
    private final Long employeeId;
    private final EmployeeDtoOut employee;
    private final LeaveType type;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final LeaveStatus status;
    private final int days;

    public LeaveRequestDtoOut(Long id, Long employeeId, EmployeeDtoOut employee, LeaveType type,
                               LocalDate startDate, LocalDate endDate, LeaveStatus status, int days) {
        this.id = id;
        this.employeeId = employeeId;
        this.employee = employee;
        this.type = type;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
        this.days = days;
    }

    public static LeaveRequestDtoOut from(LeaveRequest request) {
        EmployeeDtoOut employee = request.getEmployee() == null
                ? null
                : EmployeeDtoOut.from(request.getEmployee());

        return new LeaveRequestDtoOut(
                request.getId(),
                request.getEmployeeId(),
                employee,
                request.getType(),
                request.getStartDate(),
                request.getEndDate(),
                request.getStatus(),
                request.getDays());
    }

    public Long getId() { return id; }
    public Long getEmployeeId() { return employeeId; }
    public EmployeeDtoOut getEmployee() { return employee; }
    public LeaveType getType() { return type; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public LeaveStatus getStatus() { return status; }
    public int getDays() { return days; }
}
