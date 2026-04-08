package com.mannschaft.app.recruitment;

/**
 * F03.11 キャンセル料の段階種別。
 * PERCENTAGE: 募集料金に対する割合 (1〜100)
 * FIXED: 固定金額 (円、>0)
 */
public enum CancellationFeeType {
    PERCENTAGE,
    FIXED
}
