package com.example.leavemanagement.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "leave_requests")
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    // Controllers map to LeaveRequestDtoOut rather than serializing this entity
    // directly (see DECISIONS.md). @JsonIgnoreProperties stays as a defensive guard
    // against the Employee <-> LeaveRequest circular navigation and Hibernate-proxy
    // internals, in case this entity is ever serialized directly again.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employee_id", insertable = false, updatable = false)
    @JsonIgnoreProperties({"leaveRequests", "hibernateLazyInitializer", "handler"})
    private Employee employee;

    @Enumerated(EnumType.ORDINAL)
    @Column(nullable = false)
    private LeaveType type;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.ORDINAL)
    @Column(nullable = false)
    private LeaveStatus status = LeaveStatus.PENDING;

    // Number of calendar days the request covers (inclusive).
    @Column(nullable = false)
    private int days;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }

    public Employee getEmployee() { return employee; }
    public void setEmployee(Employee employee) { this.employee = employee; }

    public LeaveType getType() { return type; }
    public void setType(LeaveType type) { this.type = type; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public LeaveStatus getStatus() { return status; }
    public void setStatus(LeaveStatus status) { this.status = status; }

    public int getDays() { return days; }
    public void setDays(int days) { this.days = days; }
}
