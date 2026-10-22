package ph.gov.philhealth.qms.queue;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByRequestId(UUID requestId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> lockById(@Param("id") UUID id);

    /**
     * Locks the next ticket to serve: lowest priority rank first, then longest in line.
     * SKIP LOCKED lets concurrent "call next" requests at different counters each take a different
     * ticket instead of blocking or double-calling the same one.
     */
    @Query(value = """
            select * from ticket
            where site_id = :siteId and business_date = :businessDate and status = 'WAITING'
              and site_service_id in (:serviceIds)
            order by priority_rank, queued_at, seq_no
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<Ticket> lockNextWaiting(@Param("siteId") UUID siteId, @Param("businessDate") LocalDate businessDate,
                                     @Param("serviceIds") Collection<UUID> serviceIds);

    @Query("select t from Ticket t where t.counterId = :counterId and t.status = ph.gov.philhealth.qms.queue.TicketStatus.CALLED")
    List<Ticket> findCalledAtCounter(@Param("counterId") UUID counterId);

    @Query("select t from Ticket t where t.counterId = :counterId and t.status = ph.gov.philhealth.qms.queue.TicketStatus.ON_HOLD order by t.statusChangedAt")
    List<Ticket> findHeldAtCounter(@Param("counterId") UUID counterId);

    @Query("select t from Ticket t where t.siteId = :siteId and t.businessDate = :date and t.status in :statuses order by t.priorityRank, t.queuedAt")
    List<Ticket> findBySiteDateAndStatuses(@Param("siteId") UUID siteId, @Param("date") LocalDate date,
                                           @Param("statuses") Collection<TicketStatus> statuses);

    @Query("select t from Ticket t where t.siteId = :siteId and t.businessDate = :date and t.lastCalledAt is not null order by t.lastCalledAt desc limit 12")
    List<Ticket> findRecentlyCalled(@Param("siteId") UUID siteId, @Param("date") LocalDate date);

    @Query("""
            select count(t) from Ticket t where t.siteId = :siteId and t.businessDate = :date
              and t.status = ph.gov.philhealth.qms.queue.TicketStatus.WAITING and t.siteServiceId = :serviceId
              and (t.priorityRank < :rank or (t.priorityRank = :rank and t.queuedAt < :queuedAt))
            """)
    long countAhead(@Param("siteId") UUID siteId, @Param("date") LocalDate date, @Param("serviceId") UUID serviceId,
                    @Param("rank") int rank, @Param("queuedAt") java.time.Instant queuedAt);

    @Query("select t from Ticket t where t.siteId = :siteId and t.businessDate < :date and t.status in :statuses")
    List<Ticket> findOpenBefore(@Param("siteId") UUID siteId, @Param("date") LocalDate date,
                                @Param("statuses") Collection<TicketStatus> statuses);
}
