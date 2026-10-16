package com.locksteptickets.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.locksteptickets.api.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final CatalogRepository catalogRepository;

    public CatalogService(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    @Transactional(readOnly = true)
    public EventPage browse(String query, int limit, int offset) {
        List<CatalogRepository.EventSummaryRow> rows = catalogRepository.findEvents(
            query.strip(), limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        List<EventSummary> items = rows.stream()
            .limit(limit)
            .map(EventSummary::from)
            .toList();
        return new EventPage(items, limit, offset, hasMore);
    }

    @Transactional(readOnly = true)
    public EventDetail get(UUID eventId) {
        CatalogRepository.EventSummaryRow event = catalogRepository.findEvent(eventId)
            .orElseThrow(ResourceNotFoundException::new);
        boolean onSale = "ON_SALE".equals(event.state());
        List<Seat> seats = catalogRepository.findSeats(eventId).stream()
            .map(row -> Seat.from(row, onSale))
            .toList();
        return EventDetail.from(event, seats);
    }

    public record EventPage(
        List<EventSummary> items,
        int limit,
        int offset,
        boolean hasMore
    ) {
    }

    public record EventSummary(
        UUID id,
        String name,
        String state,
        Instant startsAt,
        String timeZone,
        Venue venue,
        Money fromPrice
    ) {
        static EventSummary from(CatalogRepository.EventSummaryRow row) {
            Money fromPrice = row.fromPriceMinor() == null
                ? null
                : new Money(row.fromPriceMinor(), row.currency());
            return new EventSummary(
                row.id(),
                row.name(),
                row.state(),
                row.startsAt(),
                row.timeZone(),
                new Venue(row.venueName(), row.venueAddress()),
                fromPrice);
        }
    }

    public record EventDetail(
        UUID id,
        String name,
        String state,
        Instant startsAt,
        String timeZone,
        Venue venue,
        Money fromPrice,
        Money serviceFee,
        List<Seat> seats
    ) {
        static EventDetail from(
            CatalogRepository.EventSummaryRow row,
            List<Seat> seats
        ) {
            EventSummary summary = EventSummary.from(row);
            return new EventDetail(
                summary.id(),
                summary.name(),
                summary.state(),
                summary.startsAt(),
                summary.timeZone(),
                summary.venue(),
                summary.fromPrice(),
                new Money(row.serviceFeeMinor(), row.currency()),
                seats);
        }
    }

    public record Venue(String name, String address) {
    }

    public record Money(long amountMinor, String currency) {
    }

    public record Seat(
        UUID id,
        String sectionName,
        String rowLabel,
        String seatNumber,
        boolean accessible,
        String availability,
        String priceTier,
        Money price
    ) {
        static Seat from(CatalogRepository.EventSeatRow row, boolean eventOnSale) {
            String availability = eventOnSale && "AVAILABLE".equals(row.inventoryState())
                ? "AVAILABLE"
                : "UNAVAILABLE";
            return new Seat(
                row.id(),
                row.sectionName(),
                row.rowLabel(),
                row.seatNumber(),
                row.accessible(),
                availability,
                row.tierName(),
                new Money(row.priceMinor(), row.currency()));
        }
    }
}
