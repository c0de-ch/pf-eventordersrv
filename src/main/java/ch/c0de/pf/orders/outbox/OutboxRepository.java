package ch.c0de.pf.orders.outbox;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    // Oldest pending rows first; rows locked by another relay instance are skipped, so instances never collide
    @Query(value = "select * from outbox_event where published_at is null order by id limit :limit for update skip locked",
        nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("limit") int limit);

    long countByPublishedAtIsNull();
}
