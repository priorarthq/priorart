package io.priorart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SessionTranscriptRepository extends JpaRepository<SessionTranscript, UUID> {}
