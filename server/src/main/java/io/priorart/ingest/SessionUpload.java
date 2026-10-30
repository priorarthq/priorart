package io.priorart.ingest;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

public record SessionUpload(
        @NotBlank String agent,
        @NotBlank String sessionId,
        String author,
        String cwd,
        String repo,
        String gitBranch,
        String headSha,
        Boolean hadDiff,
        Instant startedAt,
        Instant endedAt,
        String transcriptPath,
        @NotBlank String transcript
) {}
