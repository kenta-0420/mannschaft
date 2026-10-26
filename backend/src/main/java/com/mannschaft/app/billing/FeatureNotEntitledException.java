package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import lombok.Getter;

/**
 * F20.1: 402（{@code FEATURE_NOT_ENTITLED}）専用の業務例外。
 *
 * <p>購入導線情報（{@link EntitlementNotEntitledDetails}）を保持する。{@link BusinessException} を継承する
 * ため、専用ハンドラ未経由でも既存の {@code handleBusinessException} に流れて 402 として扱われる
 * （後方互換）。details を JSON に載せたい場合は {@code GlobalExceptionHandler} の専用
 * {@code @ExceptionHandler(FeatureNotEntitledException.class)} が優先して処理する。</p>
 */
@Getter
public class FeatureNotEntitledException extends BusinessException {

    private final EntitlementNotEntitledDetails details;

    public FeatureNotEntitledException(EntitlementNotEntitledDetails details) {
        super(EntitlementErrorCode.FEATURE_NOT_ENTITLED);
        this.details = details;
    }
}
