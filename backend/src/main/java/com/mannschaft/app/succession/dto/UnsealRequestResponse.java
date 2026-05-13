package com.mannschaft.app.succession.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 封緘解除申請レスポンス DTO（F09.15 S2-C）。
 *
 * <p>申請の起票から二次承認・開封完了・自動再封までのライフサイクル情報を返す。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnsealRequestResponse {

    private UUID id;
    private Long organizationId;
    private Long dwellingUnitId;
    private Long residentRegistryId;
    private UUID preRegistrationId;

    /** 申請者ユーザー ID。 */
    private Long requestedBy;

    /** 解除理由。 */
    private String requestReason;

    /** 一次承認者ユーザー ID（未承認時 null）。 */
    private Long firstApproverUserId;
    private LocalDateTime firstApprovedAt;

    /** 二次承認者ユーザー ID（未承認時 null）。 */
    private Long secondApproverUserId;
    private LocalDateTime secondApprovedAt;

    /** 開封完了日時（二次承認完了時）。 */
    private LocalDateTime unsealCompletedAt;

    /** 72h 自動再封予定日時（UNSEALED 時のみ）。 */
    private LocalDateTime autoResealAt;

    /** 再封日時（RE_SEALED 時）。 */
    private LocalDateTime reSealedAt;

    /** キャンセル日時（キャンセル時）。 */
    private LocalDateTime rejectedAt;

    /** キャンセル実施者ユーザー ID。 */
    private Long rejectedBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * {@link UnsealRequestEntity} から DTO を生成する static ファクトリメソッド。
     *
     * @param entity 封緘解除申請エンティティ
     * @return DTO インスタンス
     */
    public static UnsealRequestResponse from(UnsealRequestEntity entity) {
        return UnsealRequestResponse.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .dwellingUnitId(entity.getDwellingUnitId())
                .residentRegistryId(entity.getResidentRegistryId())
                .preRegistrationId(entity.getPreRegistrationId())
                .requestedBy(entity.getRequestedBy())
                .requestReason(entity.getRequestReason())
                .firstApproverUserId(entity.getFirstApproverUserId())
                .firstApprovedAt(entity.getFirstApprovedAt())
                .secondApproverUserId(entity.getSecondApproverUserId())
                .secondApprovedAt(entity.getSecondApprovedAt())
                .unsealCompletedAt(entity.getUnsealCompletedAt())
                .autoResealAt(entity.getAutoResealAt())
                .reSealedAt(entity.getReSealedAt())
                .rejectedAt(entity.getRejectedAt())
                .rejectedBy(entity.getRejectedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
