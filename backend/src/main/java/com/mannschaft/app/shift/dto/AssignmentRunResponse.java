package com.mannschaft.app.shift.dto;

import com.mannschaft.app.shift.AssignmentStrategyType;
import com.mannschaft.app.shift.ShiftAssignmentRunStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自動割当実行ログレスポンスDTO。
 *
 * @param id                        実行ログID
 * @param scheduleId                スケジュールID
 * @param strategy                  使用したアルゴリズム種別
 * @param status                    実行ステータス
 * @param triggeredBy               実行トリガーユーザーID
 * @param slotsTotal                対象スロット総数
 * @param slotsFilled               割当充足スロット数
 * @param warnings                  警告リスト
 * @param parameters                実行パラメータ
 * @param errorMessage              エラーメッセージ（失敗時のみ）
 * @param visualReviewConfirmedBy   目視確認ユーザーID
 * @param visualReviewConfirmedAt   目視確認日時
 * @param visualReviewNote          目視確認備考
 * @param startedAt                 実行開始日時
 * @param completedAt               実行完了日時
 * @param assignments               提案割当一覧（詳細取得時のみ）
 */
public record AssignmentRunResponse(
        Long id,
        Long scheduleId,
        AssignmentStrategyType strategy,
        ShiftAssignmentRunStatus status,
        Long triggeredBy,
        Integer slotsTotal,
        Integer slotsFilled,
        List<AssignmentWarningDto> warnings,
        AssignmentParametersDto parameters,
        String errorMessage,
        Long visualReviewConfirmedBy,
        LocalDateTime visualReviewConfirmedAt,
        String visualReviewNote,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<ProposedAssignmentDto> assignments
) {}
