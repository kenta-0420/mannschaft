package com.mannschaft.app.common.featuregate;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Gate 基盤工事③ — {@link RequireFeature} ゲート拒否の専用エラーコード。
 *
 * <p>HTTP ステータスは {@link com.mannschaft.app.common.GlobalExceptionHandler} の
 * {@code ERROR_CODE_STATUS_MAP} で <b>403 FORBIDDEN</b> にマップする（マスター裁可済み）。
 * 未登録のままだと {@code Severity.WARN} 既定の 400 に静かに落ちるため、
 * 登録は必須である（番人 {@code ErrorCodeHttpStatusDeclarationGuardTest} が検出する）。</p>
 *
 * <p>⚠️ 試練の骨格。定義のみを置いてあり、{@code ERROR_CODE_STATUS_MAP} への登録は出陣で行う。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FeatureGateErrorCode implements ErrorCode {

    /** 要求されたフィーチャーフラグが無効（または未登録＝フェイルクローズ）である。 */
    FEATURE_GATE_001("FEATURE_GATE_001",
            "この機能は現在ご利用いただけません",
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
