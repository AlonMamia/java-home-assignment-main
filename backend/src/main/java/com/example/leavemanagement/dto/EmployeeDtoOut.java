package com.example.leavemanagement.dto;

import com.example.leavemanagement.model.Employee;

// Response shape for Employee. `DtoOut` suffix marks it as data returned to API clients
// (see DECISIONS.md). Mirrors the entity's previously-serialized fields exactly
// (id, name, annualQuota) so the JSON contract is unchanged, but as a plain POJO instead
// of the JPA entity, so the leaveRequests relationship and any Hibernate-proxy internals
// can never leak into a response, even by accident.
public class EmployeeDtoOut {

    private final Long id;
    private final String name;
    private final int annualQuota;

    public EmployeeDtoOut(Long id, String name, int annualQuota) {
        this.id = id;
        this.name = name;
        this.annualQuota = annualQuota;
    }

    public static EmployeeDtoOut from(Employee employee) {
        return new EmployeeDtoOut(employee.getId(), employee.getName(), employee.getAnnualQuota());
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getAnnualQuota() { return annualQuota; }
}
