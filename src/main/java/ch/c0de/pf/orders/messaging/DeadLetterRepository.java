package ch.c0de.pf.orders.messaging;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    Optional<DeadLetter> findByMessageId(String messageId);

    List<DeadLetter> findByOrderId(Long orderId);
}
