package io.priorart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RecordRepository extends JpaRepository<Record, UUID> {
    List<Record> findBySessionId(UUID sessionId);
    List<Record> findByStatusOrderByCreatedAtDesc(String status);
    List<Record> findAllByOrderByCreatedAtDesc();
}
