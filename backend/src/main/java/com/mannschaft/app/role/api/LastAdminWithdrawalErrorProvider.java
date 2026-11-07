package com.mannschaft.app.role.api;

import com.mannschaft.app.common.ErrorCode;

/** 最後のプラットフォーム管理者の退会拒否に使う既存エラーを供給するcore所有SPI。 */
public interface LastAdminWithdrawalErrorProvider {
    ErrorCode lastAdminWithdrawalError();
}
