package com.mannschaft.app.reservation.dto;

/**
 * テンプレ保存＝同期自動生成の結果DTO（F03.4.5 §3.1）。
 *
 * <p>{@link SlotTemplateSaveResponse}（テンプレ POST/PATCH）と {@link BusinessHoursSaveResponse}
 * （営業時間 PUT）が共通で内包する。カウント 4 種は既存 {@link GenerateSlotsResponse} と同名・同義。</p>
 *
 * <p>{@code failed=true} のとき、生成段で例外が発生し保存自体は成立している状態を表す
 * （保存 tx コミット後・{@code @Transactional} の外側で生成するため保存は波及しない・§3.1）。
 * このとき翌朝の日次バッチ差分レンジ（F03.4.2 §5.4）が未生成域を自己修復する。</p>
 */
public record SlotGenerationResultDto(
        int generatedCount,
        int skippedExistingCount,
        int skippedClosedDayCount,
        int skippedOutsideHoursCount,
        boolean failed) {

    /** 生成成功時: 生成結果カウントを写して {@code failed=false} で包む。 */
    public static SlotGenerationResultDto of(GenerateSlotsResponse response) {
        return new SlotGenerationResultDto(
                response.getGeneratedCount(),
                response.getSkippedExistingCount(),
                response.getSkippedClosedDayCount(),
                response.getSkippedOutsideHoursCount(),
                false);
    }

    /**
     * 生成失敗時: {@code failed=true}。保存は成立済みのため呼び出し側は HTTP 200 系で返し、
     * FE はエラートーストで正直に報告する（症状を隠さない・§3.1）。
     */
    public static SlotGenerationResultDto ofFailure() {
        return new SlotGenerationResultDto(0, 0, 0, 0, true);
    }
}
