package com.mannschaft.app.ticket.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 複数チケット同時消化リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkConsumeRequest {

    @NotEmpty
    @Size(max = 5)
    private final List<BulkConsumeItem> consumptions;

    private final Long reservationId;

    private final Long serviceRecordId;

    private final LocalDateTime consumedAt;

    /**
     * 一括消化の個別アイテム。
     */
    @Getter
    @RequiredArgsConstructor
    public static class BulkConsumeItem {

        private final Long bookId;

        @Size(max = 500)
        private final String note;
    }
}
