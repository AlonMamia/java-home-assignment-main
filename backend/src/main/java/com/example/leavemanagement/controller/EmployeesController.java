package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.EmployeeDtoOut;
import com.example.leavemanagement.service.EmployeeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Read-only lookup so the UI can offer an employee picker.
@RestController
@RequestMapping("/api/employees")
public class EmployeesController {

    private final EmployeeService employeeService;

    public EmployeesController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    // GET /api/employees
    @GetMapping
    public List<EmployeeDtoOut> getAll() {
        return employeeService.getAll().stream()
                .map(EmployeeDtoOut::from)
                .toList();
    }
}
