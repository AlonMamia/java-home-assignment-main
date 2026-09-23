package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.LeaveRequestDtoIn;
import com.example.leavemanagement.dto.LeaveRequestDtoOut;
import com.example.leavemanagement.service.LeaveRequestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Thin HTTP adapter: translates requests/responses only. Business logic, validation
// rules and data access live in LeaveRequestService; exceptions that propagate from it
// are mapped to HTTP responses by GlobalExceptionHandler (see DECISIONS.md). Every
// response is mapped to LeaveRequestDtoOut here, at the HTTP boundary, so the service
// layer keeps working with the JPA entity.
@RestController
@RequestMapping("/api/leave-requests")
public class LeaveRequestsController {

    private final LeaveRequestService leaveRequestService;

    public LeaveRequestsController(LeaveRequestService leaveRequestService) {
        this.leaveRequestService = leaveRequestService;
    }

    // GET /api/leave-requests
    @GetMapping
    public List<LeaveRequestDtoOut> getAll() {
        return leaveRequestService.getAll().stream()
                .map(LeaveRequestDtoOut::from)
                .toList();
    }

    // GET /api/leave-requests/search?name=Dana
    // Lets the UI quickly find requests by employee name.
    @GetMapping("/search")
    public List<LeaveRequestDtoOut> search(@RequestParam String name) {
        return leaveRequestService.search(name).stream()
                .map(LeaveRequestDtoOut::from)
                .toList();
    }

    // POST /api/leave-requests
    @PostMapping
    public LeaveRequestDtoOut create(@Valid @RequestBody LeaveRequestDtoIn dto) {
        return LeaveRequestDtoOut.from(leaveRequestService.create(dto));
    }

    // POST /api/leave-requests/{id}/approve
    @PostMapping("/{id}/approve")
    public LeaveRequestDtoOut approve(@PathVariable Long id) {
        return LeaveRequestDtoOut.from(leaveRequestService.approve(id));
    }
}
