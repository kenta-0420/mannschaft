package com.mannschaft.app.receipt;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link ReceiptScopeType} の文字列解決の単体テスト（F08.4 §9.1.1 D-5 / F08.12 実機E2E 欠陥②）。
 *
 * <h2>守るバグ</h2>
 * <ul>
 *   <li>コントローラが {@code ReceiptScopeType.valueOf(scopeType.toUpperCase())} を直接呼ぶため、
 *       未知値が {@link IllegalArgumentException} となり 500 になっていた。</li>
 *   <li>テナントスコープ前提の API（admin/queue/preset/export）に {@code PLATFORM} を渡すと、
 *       {@code AccessControlService#isMember} 内の
 *       {@code ScopeType.valueOf("PLATFORM")}（membership の ScopeType に PLATFORM は無い）で
 *       {@link IllegalArgumentException} となり、やはり 500 になっていた。
 *       運営スコープの入口は {@code PlatformReceiptController}（SYSTEM_ADMIN 限定）であり、
 *       テナント API は入口で 400（COMMON_001）に落とすのが正しい。</li>
 * </ul>
 */
@DisplayName("ReceiptScopeType の文字列解決")
class ReceiptScopeTypeResolutionTest {

    @Test
    @DisplayName("from: 正規の値は大文字小文字・前後空白を問わず解決できる")
    void from_acceptsKnownValues() {
        assertThat(ReceiptScopeType.from("TEAM")).isEqualTo(ReceiptScopeType.TEAM);
        assertThat(ReceiptScopeType.from(" team ")).isEqualTo(ReceiptScopeType.TEAM);
        assertThat(ReceiptScopeType.from("organization")).isEqualTo(ReceiptScopeType.ORGANIZATION);
        assertThat(ReceiptScopeType.from("PLATFORM")).isEqualTo(ReceiptScopeType.PLATFORM);
    }

    @Test
    @DisplayName("from: 未知値・null・空文字は 400（COMMON_001）であり IllegalArgumentException を漏らさない")
    void from_rejectsUnknownValues() {
        for (String bad : new String[] {null, "", "   ", "UNKNOWN_SCOPE", "TEAMS"}) {
            BusinessException e = catchThrowableOfType(
                    () -> ReceiptScopeType.from(bad), BusinessException.class);
            assertThat(e).as("入力: %s", bad).isNotNull();
            assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_001);
        }
    }

    @Test
    @DisplayName("from: 大文字化は Locale.ROOT 固定（トルコ語ロケールでも organization を解決できる）")
    void from_usesRootLocaleForUpperCase() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertThat(ReceiptScopeType.from("organization")).isEqualTo(ReceiptScopeType.ORGANIZATION);
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("fromTenantScope: ORGANIZATION / TEAM のみ許可する")
    void fromTenantScope_acceptsTenantScopes() {
        assertThat(ReceiptScopeType.fromTenantScope("TEAM")).isEqualTo(ReceiptScopeType.TEAM);
        assertThat(ReceiptScopeType.fromTenantScope("organization"))
                .isEqualTo(ReceiptScopeType.ORGANIZATION);
    }

    @Test
    @DisplayName("fromTenantScope: PLATFORM は 400（COMMON_001）— 500 にしない")
    void fromTenantScope_rejectsPlatform() {
        assertThatThrownBy(() -> ReceiptScopeType.fromTenantScope("PLATFORM"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_001);
    }

    @Test
    @DisplayName("fromTenantScope: 未知値・空文字も 400（COMMON_001）")
    void fromTenantScope_rejectsUnknown() {
        for (String bad : new String[] {null, "", "UNKNOWN_SCOPE"}) {
            BusinessException e = catchThrowableOfType(
                    () -> ReceiptScopeType.fromTenantScope(bad), BusinessException.class);
            assertThat(e).as("入力: %s", bad).isNotNull();
            assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_001);
        }
    }
}
