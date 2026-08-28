package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.VillageEventArchiveEntity;
import com.mannschaft.app.village.entity.enums.VillageEventArchiveSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村史（行事アーカイブ）一覧の1件レスポンス（F17.2 Wave2 ⑦・設計書 §7.2/§7.4）。
 *
 * <p>編纂時に確定した「スナップショット」をそのまま返す read-only DTO。
 * {@code thumbnailUrl} は生の R2 キーではなく、{@code MediaUrlResolver} で解決済みの
 * 署名付き表示 URL（絶対 URL）を返す（#2355 r2PublicUrl 根絶の作法に揃える）。
 * DTO に Spring 依存を持ち込まないため、URL 解決は呼出元（Service）が行う。</p>
 *
 * <p><strong>実名・identity は一切含まない</strong>（G4）。{@code title}/{@code summary} は
 * 編纂時にサービス層が焼き付けたテキストのみで、ユーザーの実名・メールアドレス等は
 * 対象データに含まれない。</p>
 */
@Schema(description = "村史（行事アーカイブ）の1件（祭/歳時記/寄合の確定記録・実名非返却・§7.2）")
@Builder
public record VillageEventArchiveResponse(

        @Schema(description = "村史エントリID（UUID）", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,

        @Schema(description = "村ID（UUID）", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID villageId,

        @Schema(description = "元行事の種別（FESTIVAL/CALENDAR_EVENT/MEETUP）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        VillageEventArchiveSourceType sourceType,

        @Schema(description = "元行事のUUID（ID参照のみ）", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sourceId,

        @Schema(description = "編纂時に焼き付けた表題", requiredMode = Schema.RequiredMode.REQUIRED)
        String title,

        @Schema(description = "編纂サマリ（RSVP集計・実況件数等をテキスト化・未設定時null）")
        String summary,

        @Schema(description = "代表画像の署名付き表示URL（未設定/解決失敗時null）")
        String thumbnailUrl,

        @Schema(description = "編纂時刻", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalDateTime archivedAt
) {

    /**
     * Entity と解決済みサムネイル URL から DTO を生成する。
     *
     * @param entity       村史エンティティ
     * @param thumbnailUrl {@code MediaUrlResolver} で解決済みの表示 URL（未設定/解決失敗時は null）
     */
    public static VillageEventArchiveResponse of(VillageEventArchiveEntity entity, String thumbnailUrl) {
        return VillageEventArchiveResponse.builder()
                .id(entity.getId())
                .villageId(entity.getVillageId())
                .sourceType(entity.getSourceType())
                .sourceId(entity.getSourceId())
                .title(entity.getTitle())
                .summary(entity.getSummary())
                .thumbnailUrl(thumbnailUrl)
                .archivedAt(entity.getArchivedAt())
                .build();
    }
}
