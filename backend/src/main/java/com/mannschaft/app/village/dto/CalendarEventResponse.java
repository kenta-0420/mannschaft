package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageCalendarEventEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 歳時記カレンダーイベントレスポンス（F17.1 Phase 2 U4 §2.2）。
 *
 * <p>{@code createdByDisplayName} はサービス層で別ドメインユーザ参照を行わない方針のため、
 * 現状は常に {@code null} で返す。FE 側で必要に応じて {@code createdByUserId} を解決して
 * 表示する。将来 PostingIdentity 連携で埋めるフィールドとして予約する。</p>
 *
 * @param id                   イベント ID（UUIDv7）
 * @param villageId            所属村 ID
 * @param title                タイトル
 * @param description          詳細説明
 * @param eventDate            基準日（毎年繰返時は年無視・月日のみ意味あり）
 * @param eventEndDate         終了日（単日なら null）
 * @param isAnnualRecurring    毎年繰返すか
 * @param iconEmoji            表示絵文字
 * @param colorHex             カレンダー表示色 #RRGGBB
 * @param createdByUserId      作成者ユーザーID
 * @param createdByDisplayName 作成者表示名（現状は null 固定／将来拡張用）
 * @param createdAt            作成日時
 */
public record CalendarEventResponse(
        UUID id,
        UUID villageId,
        String title,
        String description,
        LocalDate eventDate,
        LocalDate eventEndDate,
        Boolean isAnnualRecurring,
        String iconEmoji,
        String colorHex,
        Long createdByUserId,
        String createdByDisplayName,
        LocalDateTime createdAt
) {

    /** Entity からレスポンス DTO を生成する。{@code createdByDisplayName} は null 固定。 */
    public static CalendarEventResponse from(VillageCalendarEventEntity entity) {
        return new CalendarEventResponse(
                entity.getId(),
                entity.getVillageId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getEventDate(),
                entity.getEventEndDate(),
                entity.getIsAnnualRecurring(),
                entity.getIconEmoji(),
                entity.getColorHex(),
                entity.getCreatedByUserId(),
                null,
                entity.getCreatedAt());
    }
}
