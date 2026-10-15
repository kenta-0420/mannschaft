package com.mannschaft.app.errorreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * アクティブインシデント一覧レスポンス。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActiveIncidentResponse {

    private List<Incident> incidents;

    /**
     * 個別インシデント情報。
     */
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Incident {
        private String pagePattern;
        private String message;
        private String severity;
        private LocalDateTime since;
    }
}
