package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.BusinessException;
import lombok.Getter;

/**
 * CMP-260901-1538 柱③-A: 409（{@code DUPNAME_001}）専用の業務例外。
 *
 * <p>金型: {@code com.mannschaft.app.billing.FeatureNotEntitledException}。候補一覧・fingerprint
 * （{@link DuplicateNameConfirmationDetails}）を保持する。{@link BusinessException} を継承するため
 * 専用ハンドラ未経由でも {@code handleBusinessException} に流れる（後方互換）。</p>
 */
@Getter
public class DuplicateNameConfirmationRequiredException extends BusinessException {

    private final DuplicateNameConfirmationDetails details;

    public DuplicateNameConfirmationRequiredException(DuplicateNameConfirmationDetails details) {
        super(DuplicateNameErrorCode.DUPNAME_001);
        this.details = details;
    }
}
