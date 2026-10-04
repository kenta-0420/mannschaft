package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** 中央HTTP写像と既存メール確認エラーの意味を別々に維持する。認可の実DB証拠にはしない。 */
class UserOperationErrorCodeTest {
    @Test @DisplayName("受付容量不足は専用503、利用不可は存在区別なし403")
    void operationErrorsKeepDedicatedStatuses() {
        assertThat(UserOperationErrorCode.UNAVAILABLE.getCode()).isEqualTo("AUTHOPERATION_001");
        assertThat(GlobalExceptionHandler.resolveStatus(UserOperationErrorCode.UNAVAILABLE)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(UserOperationErrorCode.NOT_ALLOWED.getCode()).isEqualTo("AUTHOPERATION_002");
        assertThat(GlobalExceptionHandler.resolveStatus(UserOperationErrorCode.NOT_ALLOWED)).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test @DisplayName("AUTH005のメール確認トークン失効と400は変更しない")
    void existingMailTokenErrorUnchanged() {
        assertThat(AuthErrorCode.AUTH_005.getCode()).isEqualTo("AUTH_005");
        assertThat(AuthErrorCode.AUTH_005.getMessage()).isEqualTo("確認メールのトークンが無効または期限切れです");
        assertThat(GlobalExceptionHandler.resolveStatus(AuthErrorCode.AUTH_005)).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
