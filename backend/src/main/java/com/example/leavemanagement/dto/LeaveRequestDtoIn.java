package com.example.leavemanagement.dto;

import com.example.leavemanagement.model.LeaveType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

// Incoming payload for creating a leave request. `DtoIn` suffix marks it as data
// received from API clients (see DECISIONS.md). Field-presence validation only
// (@NotNull): date-range and business-rule validation (e.g. end not before start,
// quota checks) are business logic and belong in LeaveRequestService, not here.
public class LeaveRequestDtoIn {

    @NotNull
    private Long employeeId;

    @NotNull
    private LeaveType type;

    @NotNull
    private LocalDate startDate;

    @NotNull
    private LocalDate endDate;

    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }

    public LeaveType getType() { return type; }
    public void setType(LeaveType type) { this.type = type; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
}
