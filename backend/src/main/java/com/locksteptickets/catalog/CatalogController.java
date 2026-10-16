package com.locksteptickets.catalog;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@Validated
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    CatalogService.EventPage browse(
        @RequestParam(defaultValue = "") @Size(max = 100) String query,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
        @RequestParam(defaultValue = "0") @Min(0) @Max(5000) int offset
    ) {
        return catalogService.browse(query, limit, offset);
    }

    @GetMapping("/{eventId}")
    CatalogService.EventDetail get(@PathVariable UUID eventId) {
        return catalogService.get(eventId);
    }
}
