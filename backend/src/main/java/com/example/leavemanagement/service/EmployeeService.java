package com.example.leavemanagement.service;

import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.repository.EmployeeRepository;
import org.springframework.stereotype.Service;

import java.util.List;

// Read-only lookup so the UI can offer an employee picker. No business rules apply here
// today, but the controller still goes through this service rather than the repository
// directly, so every data-access path is reachable from one consistent layer.
@Service
public class EmployeeService {

    private final EmployeeRepository employeeRepository;

    public EmployeeService(EmployeeRepository employeeRepository) {
        this.employeeRepository = employeeRepository;
    }

    public List<Employee> getAll() {
        return employeeRepository.findAll();
    }
}
