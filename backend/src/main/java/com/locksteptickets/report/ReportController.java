package com.locksteptickets.report;

import java.util.UUID;

import com.locksteptickets.auth.AuthenticatedAccount;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/admin/reports/events/{eventId}")
public class ReportController {
    private final ReportService service;ReportController(ReportService service){this.service=service;}
    @GetMapping("/sales") ReportService.SalesReport sales(@PathVariable UUID eventId,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.sales(eventId,actor);}
    @GetMapping("/occupancy") ReportService.OccupancyReport occupancy(@PathVariable UUID eventId,
        @AuthenticationPrincipal AuthenticatedAccount actor){return service.occupancy(eventId,actor);}
}
