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
     * 生成が 1 チャンクもコミットする前に失敗した時（真に 0 件）: 全カウント 0＋{@code failed=true}。
     * 保存は成立済みのため呼び出し側は HTTP 200 系で返し、FE はエラートーストで正直に報告する（症状を隠さない・§3.1）。
     */
    public static SlotGenerationResultDto ofFailure() {
        return new SlotGenerationResultDto(0, 0, 0, 0, true);
    }

    /**
     * 生成が<b>1 つ以上の日付チャンクをコミットした後で</b>失敗した時（部分実行）:
     * <b>コミット済み分の実カウント</b>＋{@code failed=true}（F03.4.5 §3.1 契約）。
     * 途中失敗でも先行チャンクは永続化済みのため、その実件数を正直に報告する
     * （0 件で報告するとトーストが嘘になり症状の黙殺になる）。
     */
    public static SlotGenerationResultDto ofPartialFailure(GenerateSlotsResponse accumulated) {
        return new SlotGenerationResultDto(
                accumulated.getGeneratedCount(),
                accumulated.getSkippedExistingCount(),
                accumulated.getSkippedClosedDayCount(),
                accumulated.getSkippedOutsideHoursCount(),
                true);
    }
}
