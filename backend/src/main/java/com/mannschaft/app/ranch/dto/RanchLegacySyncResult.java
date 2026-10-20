package com.mannschaft.app.ranch.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 同key再送で固定される、旧記念品取込の件数と次の位置だけ。 */
public record RanchLegacySyncResult(UUID commandId, String nextAfterAwardId,
                                    int processedCount, int importedCount,
                                    boolean hasNext, Instant completedAt) {
    public RanchLegacySyncResult {
        Objects.requireNonNull(commandId);
        Objects.requireNonNull(nextAfterAwardId);
        Objects.requireNonNull(completedAt);
        if (!nextAfterAwardId.matches("0|[1-9][0-9]*")
                || processedCount < 0 || processedCount > 100
                || importedCount < 0 || importedCount > processedCount) {
            throw new IllegalArgumentException("取込結果の件数またはcursorが不正です");
        }
    }
}
