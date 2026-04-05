package com.mannschaft.app.receipt;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.receipt.dto.UpdateIssuerSettingsRequest;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import com.mannschaft.app.receipt.service.ReceiptIssuerSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReceiptIssuerSettingsService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptIssuerSettingsService 単体テスト")
class ReceiptIssuerSettingsServiceTest {

    @Mock private ReceiptIssuerSettingsRepository issuerSettingsRepository;
    @Mock private ReceiptMapper receiptMapper;

    @InjectMocks
    private ReceiptIssuerSettingsService service;

    private static final ReceiptScopeType SCOPE_TYPE = ReceiptScopeType.TEAM;
    private static final Long SCOPE_ID = 1L;

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        @DisplayName("異常系: 設定が見つからない")
        void 設定不存在() {
            given(issuerSettingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSettings(SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.ISSUER_SETTINGS_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("upsertSettings")
    class UpsertSettings {

        @Test
        @DisplayName("異常系: 適格請求書発行事業者で登録番号未設定はエラー")
        void 登録番号未設定() {
            UpdateIssuerSettingsRequest request = new UpdateIssuerSettingsRequest(
                    "テスト組織", null, null, null, true, null,
                    null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.upsertSettings(SCOPE_TYPE, SCOPE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.INVOICE_REGISTRATION_NUMBER_REQUIRED);
        }

        @Test
        @DisplayName("異常系: 登録番号形式不正（T + 13桁でない）")
        void 登録番号形式不正() {
            UpdateIssuerSettingsRequest request = new UpdateIssuerSettingsRequest(
                    "テスト組織", null, null, null, true, "TXXX",
                    null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.upsertSettings(SCOPE_TYPE, SCOPE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.INVALID_INVOICE_REGISTRATION_NUMBER);
        }

        @Test
        @DisplayName("正常系: 新規作成（UPSERT）")
        void 新規作成() {
            UpdateIssuerSettingsRequest request = new UpdateIssuerSettingsRequest(
                    "テスト組織", null, null, null, true, "T1234567890123",
                    null, null, null, null, null, null, null);
            given(issuerSettingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());
            ReceiptIssuerSettingsEntity saved = ReceiptIssuerSettingsEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).issuerName("テスト組織").build();
            given(issuerSettingsRepository.save(any())).willReturn(saved);
            given(receiptMapper.toIssuerSettingsResponse(saved)).willReturn(null);

            service.upsertSettings(SCOPE_TYPE, SCOPE_ID, request);

            verify(issuerSettingsRepository).save(any());
        }
    }
}
