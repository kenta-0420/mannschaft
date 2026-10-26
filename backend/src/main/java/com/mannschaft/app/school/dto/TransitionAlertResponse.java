package com.mannschaft.app.school.dto;

import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.school.entity.AttendanceTransitionAlertEntity;
import com.mannschaft.app.school.entity.TransitionAlertLevel;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 移動検知アラートレスポンスDTO。 */
@Getter
@Builder
public class TransitionAlertResponse {

    /** アラートID。 */
    private Long id;

    /** クラスチームID。 */
    private Long teamId;

    /** 生徒ユーザーID。 */
    private Long studentUserId;

    /** 対象日。 */
    private LocalDate attendanceDate;

    /** 直前時限番号（出席だった時限）。 */
    private Integer previousPeriodNumber;

    /** 現在時限番号（欠席になった時限）。 */
    private Integer currentPeriodNumber;

    /** 直前時限の出欠状態。 */
    private AttendanceStatus previousPeriodStatus;

    /** 現在時限の出欠状態。 */
    private AttendanceStatus currentPeriodStatus;

    /** アラートレベル（NORMAL / URGENT）。 */
    private TransitionAlertLevel alertLevel;

    /** 解決済みかどうか。 */
    private boolean resolved;

    /** 解決日時（未解決の場合 null）。 */
    private LocalDateTime resolvedAt;

    /** 解決者ユーザーID（未解決の場合 null）。 */
    private Long resolvedBy;

    /** 解決理由（未解決の場合 null）。 */
    private String resolutionNote;

    /** 検知日時。 */
    private LocalDateTime createdAt;

    /**
     * エンティティから TransitionAlertResponse を生成するファクトリメソッド。
     *
     * @param entity 移動検知アラートエンティティ
     * @return TransitionAlertResponse
     */
    public static TransitionAlertResponse from(AttendanceTransitionAlertEntity entity) {
        return TransitionAlertResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .studentUserId(entity.getStudentUserId())
                .attendanceDate(entity.getAttendanceDate())
                .previousPeriodNumber(entity.getPreviousPeriodNumber())
                .currentPeriodNumber(entity.getCurrentPeriodNumber())
                .previousPeriodStatus(entity.getPreviousPeriodStatus())
                .currentPeriodStatus(entity.getCurrentPeriodStatus())
                .alertLevel(entity.getAlertLevel())
                .resolved(entity.getResolvedAt() != null)
                .resolvedAt(entity.getResolvedAt())
                .resolvedBy(entity.getResolvedBy())
                .resolutionNote(entity.getResolutionNote())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
