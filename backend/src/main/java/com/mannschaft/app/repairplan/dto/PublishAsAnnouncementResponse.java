package com.mannschaft.app.repairplan.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * シナリオを告知として公開した結果 DTO（F08.8 Phase 2）。
 */
public record PublishAsAnnouncementResponse(
        UUID scenarioId,
        int versionNo,
        LocalDateTime lockedAt,
        Long announcementId,
        String announcementStatus
) {}
