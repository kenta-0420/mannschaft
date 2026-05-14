package com.mannschaft.app.village;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F17.1 村機能のエラーコード定義。
 *
 * <p>Phase 1 B5（村作成申請）で追加。番号は B2/B3/B4 と被らないよう、
 * 申請関連は 015〜029 を割り当てる。</p>
 */
@Getter
@RequiredArgsConstructor
public enum VillageErrorCode implements ErrorCode {

    /** ガイドライン未同意（または同意期限切れ） */
    VILLAGE_015("VILLAGE_015", "村ガイドラインへの同意が必要です（直近1時間以内）", Severity.WARN),

    /** 申請レートリミット超過（1日3件 or 保有 PENDING 10件） */
    VILLAGE_017("VILLAGE_017", "村作成申請のレートリミットを超過しています（1日3件・保有10件まで）", Severity.WARN),

    /** 申請が存在しない */
    VILLAGE_018("VILLAGE_018", "村作成申請が見つかりません", Severity.WARN),

    /** 既に審査済み */
    VILLAGE_019("VILLAGE_019", "この村作成申請は既に審査済みです", Severity.WARN),

    /** 拒否済みのため操作不可 */
    VILLAGE_023("VILLAGE_023", "この村作成申請は拒否済みです", Severity.WARN),

    /** slug が既存村と衝突 */
    VILLAGE_027("VILLAGE_027", "指定された slug は既に使用されています", Severity.WARN),

    /** 一般ユーザーが OFFICIAL 村を申請しようとした */
    VILLAGE_028("VILLAGE_028", "一般ユーザーは公式村を申請できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
