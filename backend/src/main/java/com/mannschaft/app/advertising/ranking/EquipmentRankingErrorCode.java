package com.mannschaft.app.advertising.ranking;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 備品ランキング機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum EquipmentRankingErrorCode implements ErrorCode {

    /** ランキングデータが準備中（初回バッチ未実行）（503） */
    RANKING_NOT_READY("ERANK_001", "ランキングデータが準備中です", Severity.WARN),

    /** 二重opt-out（409） */
    ALREADY_OPT_OUT("ERANK_002", "すでにオプトアウト済みです", Severity.WARN),

    /** opt-out未設定でDELETE（404） */
    OPT_OUT_NOT_FOUND("ERANK_003", "オプトアウトが設定されていません", Severity.WARN),

    /** 除外設定が見つからない（404） */
    EXCLUSION_NOT_FOUND("ERANK_004", "除外設定が見つかりません", Severity.WARN),

    /** バッチ実行中の二重起動（409） */
    BATCH_ALREADY_RUNNING("ERANK_005", "ランキング集計バッチが実行中です", Severity.WARN),

    /** 除外設定の重複（409） */
    DUPLICATE_EXCLUSION("ERANK_006", "同一アイテムの除外設定が既に存在します", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
