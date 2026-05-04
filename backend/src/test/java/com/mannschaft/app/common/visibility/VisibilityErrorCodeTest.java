package com.mannschaft.app.common.visibility;

import com.mannschaft.app.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VisibilityErrorCode} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.4。
 *
 * <p>4 コード (VISIBILITY_001〜004) すべてが {@link ErrorCode} を実装し、
 * code/message/severity が non-null であることを確認する。
 */
@DisplayName("VisibilityErrorCode")
class VisibilityErrorCodeTest {

    @Test
    @DisplayName("4 コードすべてが ErrorCode を実装している")
    void implements_errorCode() {
        for (VisibilityErrorCode value : VisibilityErrorCode.values()) {
            assertThat(value).isInstanceOf(ErrorCode.class);
        }
        assertThat(VisibilityErrorCode.values()).hasSize(4);
    }

    @Test
    @DisplayName("VISIBILITY_001 — 認可拒否 / WARN")
    void visibility_001_isAuthorizationDeny() {
        VisibilityErrorCode code = VisibilityErrorCode.VISIBILITY_001;
        assertThat(code.getCode()).isEqualTo("VISIBILITY_001");
        assertThat(code.getMessage()).isNotBlank();
        assertThat(code.getSeverity()).isEqualTo(ErrorCode.Severity.WARN);
    }

    @Test
    @DisplayName("VISIBILITY_002 — 不正な reference_type / WARN")
    void visibility_002_isInvalidReferenceType() {
        VisibilityErrorCode code = VisibilityErrorCode.VISIBILITY_002;
        assertThat(code.getCode()).isEqualTo("VISIBILITY_002");
        assertThat(code.getMessage()).isNotBlank();
        assertThat(code.getSeverity()).isEqualTo(ErrorCode.Severity.WARN);
    }

    @Test
    @DisplayName("VISIBILITY_003 — 内部 Resolver エラー / ERROR")
    void visibility_003_isInternalError() {
        VisibilityErrorCode code = VisibilityErrorCode.VISIBILITY_003;
        assertThat(code.getCode()).isEqualTo("VISIBILITY_003");
        assertThat(code.getMessage()).isNotBlank();
        assertThat(code.getSeverity()).isEqualTo(ErrorCode.Severity.ERROR);
    }

    @Test
    @DisplayName("VISIBILITY_004 — 対象コンテンツ不在 / WARN")
    void visibility_004_isNotFound() {
        VisibilityErrorCode code = VisibilityErrorCode.VISIBILITY_004;
        assertThat(code.getCode()).isEqualTo("VISIBILITY_004");
        assertThat(code.getMessage()).isNotBlank();
        assertThat(code.getSeverity()).isEqualTo(ErrorCode.Severity.WARN);
    }

    @Test
    @DisplayName("全コードが non-null フィールドを持つ")
    void allCodes_haveNonNullFields() {
        for (VisibilityErrorCode value : VisibilityErrorCode.values()) {
            assertThat(value.getCode()).as("code").isNotBlank();
            assertThat(value.getMessage()).as("message").isNotBlank();
            assertThat(value.getSeverity()).as("severity").isNotNull();
        }
    }
}
