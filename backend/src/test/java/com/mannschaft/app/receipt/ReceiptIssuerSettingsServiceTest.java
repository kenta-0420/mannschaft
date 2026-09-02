package com.mannschaft.app.receipt;

import com.mannschaft.app.common.AccessControlService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReceiptIssuerSettingsService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptIssuerSettingsService 単体テスト")
class ReceiptIssuerSettingsServiceTest {

    @Mock private ReceiptIssuerSettingsRepository issuerSettingsRepository;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private AccessControlService accessControlService;

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

            assertThatThrownBy(() -> service.getSettings(SCOPE_TYPE, SCOPE_ID, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.ISSUER_SETTINGS_NOT_FOUND);
        }

        /**
         * AC-11 / D-6（試練・実装前 red）。
         *
         * <p>発行者設定には住所・電話・登録番号・次番号が含まれるため、同一スコープの
         * 一般メンバーに開示してはならない。現行は {@code checkMembership} なので red。
         * HTTP 越しの 403 検証は {@code ReceiptIssuerSettingsContractIT} が担う。</p>
         */
        @Test
        @DisplayName("AC-11(red): getSettings は checkAdminOrAbove で認可する（checkMembership ではない）")
        void 閲覧認可は管理者以上() {
            ReceiptIssuerSettingsEntity existing = ReceiptIssuerSettingsEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).issuerName("発行者").build();
            given(issuerSettingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(existing));

            service.getSettings(SCOPE_TYPE, SCOPE_ID, 100L);

            verify(accessControlService).checkAdminOrAbove(100L, SCOPE_ID, SCOPE_TYPE.name());
            verify(accessControlService, never()).checkMembership(any(), any(), any());
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

            assertThatThrownBy(() -> service.upsertSettings(SCOPE_TYPE, SCOPE_ID, 100L, request))
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

            assertThatThrownBy(() -> service.upsertSettings(SCOPE_TYPE, SCOPE_ID, 100L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.INVALID_INVOICE_REGISTRATION_NUMBER);
        }

        // ───────────── 試練（実装前 red）: 不変条件はマージ後の状態に対して検証する ─────────────
        //
        // 正本: docs/features/F08.4_receipt.md §9.2「不変条件はマージ後の状態に対して検証する」/ AC-35。
        // 現行の validateInvoiceRegistration() は request.getIsQualifiedInvoicer() が TRUE の
        // ときだけ検証するため、下記 3 件はいずれも素通りして red になる。

        @Test
        @DisplayName("AC-35(red): DBが適格TRUEのまま登録番号を空文字でクリアするとRECEIPT_007")
        void マージ後_適格のまま登録番号クリア_エラー() {
            ReceiptIssuerSettingsEntity existing = ReceiptIssuerSettingsEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .issuerName("既存の発行者名")
                    .isQualifiedInvoicer(true)
                    .invoiceRegistrationNumber("T1234567890123")
                    .build();
            given(issuerSettingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(existing));

            // isQualifiedInvoicer は送らない（null = 無変更）。登録番号だけを明示クリアする。
            UpdateIssuerSettingsRequest request = new UpdateIssuerSettingsRequest(
                    null, null, null, null, null, "",
                    null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.upsertSettings(SCOPE_TYPE, SCOPE_ID, 100L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.INVOICE_REGISTRATION_NUMBER_REQUIRED);
        }

        @Test
        @DisplayName("AC-35(red): DBが適格TRUEのまま登録番号を不正形式へ変えるとRECEIPT_006")
        void マージ後_適格のまま登録番号形式不正_エラー() {
            ReceiptIssuerSettingsEntity existing = ReceiptIssuerSettingsEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .issuerName("既存の発行者名")
                    .isQualifiedInvoicer(true)
                    .invoiceRegistrationNumber("T1234567890123")
                    .build();
            given(issuerSettingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(existing));

            UpdateIssuerSettingsRequest request = new UpdateIssuerSettingsRequest(
                    null, null, null, null, null, "T123",
                    null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.upsertSettings(SCOPE_TYPE, SCOPE_ID, 100L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.INVALID_INVOICE_REGISTRATION_NUMBER);
        }

        @Test
        @DisplayName("AC-23(red): 未作成スコープでissuerNameを欠く差分更新はRECEIPT_007ではなく作成させない")
        void マージ後_新規作成で発行者名欠落_保存しない() {
            given(issuerSettingsRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            UpdateIssuerSettingsRequest request = new UpdateIssuerSettingsRequest(
                    null, null, null, null, false, null,
                    null, null, null, null, null, null, "フッターだけ");

            assertThatThrownBy(() -> service.upsertSettings(SCOPE_TYPE, SCOPE_ID, 100L, request))
                    .isInstanceOf(BusinessException.class);
            verify(issuerSettingsRepository, never()).save(any());
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

            service.upsertSettings(SCOPE_TYPE, SCOPE_ID, 100L, request);

            verify(issuerSettingsRepository).save(any());
        }
    }
}
