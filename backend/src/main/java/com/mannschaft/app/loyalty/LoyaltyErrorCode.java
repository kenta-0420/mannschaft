package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** チームポイントの業務契約。 */
@Getter
@RequiredArgsConstructor
public enum LoyaltyErrorCode implements ErrorCode {
    LOYALTY_001("LOYALTY_001", "対象が見つかりません", Severity.WARN),
    LOYALTY_002("LOYALTY_002", "このチームの操作権限がありません", Severity.WARN),
    LOYALTY_003("LOYALTY_003", "現在の付与対象ではありません", Severity.WARN),
    LOYALTY_004("LOYALTY_004", "ポイントプログラムまたは操作が停止しています", Severity.WARN),
    LOYALTY_005("LOYALTY_005", "本日のポイントは付与済みです", Severity.WARN),
    LOYALTY_006("LOYALTY_006", "同じキーに異なる内容が指定されています", Severity.WARN),
    LOYALTY_007("LOYALTY_007", "内容が変更されました。再確認してください", Severity.WARN),
    LOYALTY_008("LOYALTY_008", "ポイント残高が不足しています", Severity.WARN),
    LOYALTY_009("LOYALTY_009", "ポイント上限または入力の組合せが不正です", Severity.WARN),
    LOYALTY_010("LOYALTY_010", "会員QRが無効です", Severity.WARN),
    LOYALTY_011("LOYALTY_011", "受付QRのチームが一致しません", Severity.WARN),
    LOYALTY_012("LOYALTY_012", "操作回数の上限を超えました", Severity.WARN),
    LOYALTY_013("LOYALTY_013", "申請の状態または確認内容が一致しません", Severity.WARN),
    LOYALTY_014("LOYALTY_014", "申請が失効しました", Severity.WARN),
    LOYALTY_015("LOYALTY_015", "自分の申請は承認できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
