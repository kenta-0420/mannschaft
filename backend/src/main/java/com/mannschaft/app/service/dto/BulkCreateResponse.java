package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 一括作成レスポンス。
 */
@Getter
@Builder
public class BulkCreateResponse {

    private Integer createdCount;
    private Integer failedCount;
    private List<BulkResultEntry> results;
    private List<BulkRecordEntry> records;

    @Getter
    @Builder
    public static class BulkResultEntry {
        private Integer index;
        private String status;
        private Long id;
        private String title;
        private String error;
    }

    @Getter
    @Builder
    public static class BulkRecordEntry {
        private Long id;
        private String title;
        private String status;
    }
}
