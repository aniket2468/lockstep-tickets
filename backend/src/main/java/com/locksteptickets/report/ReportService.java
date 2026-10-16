package com.locksteptickets.report;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.locksteptickets.api.ResourceNotFoundException;
import com.locksteptickets.auth.Account;
import com.locksteptickets.auth.AuthenticatedAccount;
import com.locksteptickets.hold.HoldRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    private final ReportRepository repository;private final Clock clock;
    ReportService(ReportRepository repository,Clock clock){this.repository=repository;this.clock=clock;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public SalesReport sales(UUID eventId,AuthenticatedAccount actor){requireAdmin(actor);
        var row=repository.sales(eventId).orElseThrow(ResourceNotFoundException::new);
        long net=Math.subtractExact(row.gross(),row.refunded());
        return new SalesReport(row.eventId(),row.currency(),row.bookingCount(),row.ticketCount(),
            row.activeTicketCount(),row.refundedTicketCount(),row.gross(),row.refunded(),row.pending(),net);}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public OccupancyReport occupancy(UUID eventId,AuthenticatedAccount actor){requireAdmin(actor);
        Instant now=clock.instant();var row=repository.occupancy(eventId,now).orElseThrow(ResourceNotFoundException::new);
        long reconciled=row.blocked()+row.sold()+row.paymentPending()+row.waitlistOffered()+row.held()+row.unallocated();
        if(reconciled!=row.total())throw new IllegalStateException("Occupancy buckets do not reconcile.");
        boolean salesOpen="ON_SALE".equals(row.eventState())&&!now.isBefore(row.salesStartAt())&&now.isBefore(row.salesEndAt());
        return new OccupancyReport(row.eventId(),row.eventState(),salesOpen,row.total(),row.blocked(),row.sold(),
            row.paymentPending(),row.waitlistOffered(),row.held(),row.unallocated(),reconciled);}

    private void requireAdmin(AuthenticatedAccount actor){if(actor.role()!=Account.Role.ADMIN)
        throw new HoldRejectedException(HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access is denied.");}
    public record SalesReport(UUID eventId,String currency,long bookingCount,long ticketCount,
        long activeTicketCount,long refundedTicketCount,long grossSalesMinor,long refundedMinor,
        long pendingRefundMinor,long netSalesMinor){}
    public record OccupancyReport(UUID eventId,String eventState,boolean salesOpen,long totalSeats,
        long blockedSeats,long soldSeats,long paymentPendingSeats,long waitlistOfferedSeats,long heldSeats,
        long unallocatedSeats,long reconciledSeats){}
}
