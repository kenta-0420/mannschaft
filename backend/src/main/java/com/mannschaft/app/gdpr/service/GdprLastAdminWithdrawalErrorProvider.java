package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.role.api.LastAdminWithdrawalErrorProvider;
import org.springframework.stereotype.Component;

/** 退会拒否のエラー定義をgdprに保ち、既存enumを認可SPIへ返す。 */
@Component
public class GdprLastAdminWithdrawalErrorProvider implements LastAdminWithdrawalErrorProvider {
    @Override
    public ErrorCode lastAdminWithdrawalError() {
        return GdprErrorCode.GDPR_006;
    }
}
