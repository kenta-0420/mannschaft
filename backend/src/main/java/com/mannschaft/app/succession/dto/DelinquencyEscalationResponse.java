package com.mannschaft.app.succession.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 滞納エスカレーションレスポンス DTO（F09.15 S5-B）。
 *
 * <p>エスカレーションの全ライフサイクル情報（ステージ進行・凍結・解決）を返す。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DelinquencyEscalationResponse {

    /** エスカレーション ID（UUID 文字列）。 */
    private String id;

    /** テナント組織 ID。 */
    private Long organizationId;

    /** 居住者台帳 ID。 */
    private Long residentRegistryId;

    /** 居住ユニット ID。 */
    private Long dwellingUnitId;

    /**
     * 現在のエスカレーションステージ。
     * STAGE_1_REMINDER / STAGE_2_EMERGENCY_CONTACT / STAGE_3_WATCHER_VISIT /
     * STAGE_4_DEATH_SUSPECTED / STAGE_5_LEGAL_PREP
     */
    private String currentStage;

    /** 滞納開始日（D+0）。 */
    private LocalDate delinquencyStartedAt;

    /** ステージ 1（督促通知）完了日時。 */
    private LocalDateTime stage1CompletedAt;

    /** ステージ 2（緊急連絡先通知）完了日時。 */
    private LocalDateTime stage2CompletedAt;

    /** ステージ 3（見守り訪問）完了日時。 */
    private LocalDateTime stage3CompletedAt;

    /** ステージ 4（死亡疑い確認）完了日時。 */
    private LocalDateTime stage4CompletedAt;

    /** ステージ 5（法的手続き準備）完了日時。 */
    private LocalDateTime stage5CompletedAt;

    /** エスカレーション凍結日時（弁護士介入等、凍結していない場合 null）。 */
    private LocalDateTime frozenAt;

    /** 凍結理由（凍結していない場合 null）。 */
    private String frozenReason;

    /** エスカレーション解決日時（未解決の場合 null）。 */
    private LocalDateTime resolvedAt;

    /** 解決理由（PAID / DEATH_CONFIRMED / MANUAL_CLOSE 等、未解決の場合 null）。 */
    private String resolvedReason;

    /** レコード作成日時。 */
    private LocalDateTime createdAt;

    /** レコード更新日時。 */
    private LocalDateTime updatedAt;

    /**
     * {@link DelinquencyEscalationEntity} から DTO を生成する static ファクトリメソッド。
     *
     * @param entity 滞納エスカレーションエンティティ
     * @return DTO インスタンス
     */
    public static DelinquencyEscalationResponse fromEntity(DelinquencyEscalationEntity entity) {
        UUID entityId = entity.getId();
        return DelinquencyEscalationResponse.builder()
                .id(entityId != null ? entityId.toString() : null)
                .organizationId(entity.getOrganizationId())
                .residentRegistryId(entity.getResidentRegistryId())
                .dwellingUnitId(entity.getDwellingUnitId())
                .currentStage(entity.getCurrentStage())
                .delinquencyStartedAt(entity.getDelinquencyStartedAt())
                .stage1CompletedAt(entity.getStage1CompletedAt())
                .stage2CompletedAt(entity.getStage2CompletedAt())
                .stage3CompletedAt(entity.getStage3CompletedAt())
                .stage4CompletedAt(entity.getStage4CompletedAt())
                .stage5CompletedAt(entity.getStage5CompletedAt())
                .frozenAt(entity.getFrozenAt())
                .frozenReason(entity.getFrozenReason())
                .resolvedAt(entity.getResolvedAt())
                .resolvedReason(entity.getResolvedReason())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
