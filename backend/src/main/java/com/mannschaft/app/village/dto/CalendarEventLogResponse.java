package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageCalendarEventLogEntity;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.2 Wave1 ④歳時記×村史の年輪 — 年輪レスポンス（設計書 §6.4/AC-19）。
 *
 * <p>{@code photoUrl} は生の R2 キーではなく {@code MediaUrlResolver} で解決済みの署名付き表示 URL を返す
 * （未設定 / 解決失敗時は {@code null}・#2355 の r2PublicUrl 根絶方針）。一覧は {@code resolveAll} で
 * N+1 を回避する（AC-19）。{@code createdByDisplayName} は<strong>村ニックネーム</strong>で解決する
 * （実名スナップショット禁止・§10 G4）。</p>
 */
@Builder
public record CalendarEventLogResponse(
        UUID id,
        UUID calendarEventId,
        Integer year,
        String photoUrl,
        String note,
        Long createdByUserId,
        String createdByDisplayName,
        LocalDateTime createdAt) {

    /**
     * Entity から DTO を生成する。
     *
     * @param photoUrl            解決済み署名付き URL（{@code MediaUrlResolver} 由来・null 許容）
     * @param createdByDisplayName 村ニックネーム表示名（null 許容）
     */
    public static CalendarEventLogResponse of(VillageCalendarEventLogEntity entity,
                                              String photoUrl, String createdByDisplayName) {
        return CalendarEventLogResponse.builder()
                .id(entity.getId())
                .calendarEventId(entity.getCalendarEventId())
                .year(entity.getYear())
                .photoUrl(photoUrl)
                .note(entity.getNote())
                .createdByUserId(entity.getCreatedByUserId())
                .createdByDisplayName(createdByDisplayName)
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
