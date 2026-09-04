package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.ReceiptScopes;
import com.mannschaft.app.receipt.dto.IssuerSettingsResponse;
import com.mannschaft.app.receipt.dto.PageResponse;
import com.mannschaft.app.receipt.dto.PlatformReceiptSummaryResponse;
import com.mannschaft.app.receipt.dto.UpdateIssuerSettingsRequest;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 運営コンソール（SYSTEM_ADMIN）向けの運営領収書サービス（F08.12 §4.1）。
 *
 * <p><b>認可</b>: 本 Service を呼ぶ Controller は {@code /api/v1/system-admin/**} 配下にあり、
 * {@code SecurityConfig} のパス認可（{@code hasRole("SYSTEM_ADMIN")}）でフィルタチェーンが
 * 先に 403 を返す。加えて本 Service でも
 * {@link AccessControlService#checkAdminOrAboveIncludingPlatform} を呼び、バッチ等の
 * 別経路から呼ばれた場合の二重防御とする。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformReceiptService {

    /** 一覧の最大ページサイズ。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final ReceiptRepository receiptRepository;
    private final ReceiptIssuerSettingsRepository issuerSettingsRepository;
    private final ReceiptIssuerSettingsService issuerSettingsService;
    private final AccessControlService accessControlService;

    /**
     * PLATFORM 発行者設定を取得する。
     *
     * <p><b>未登録でも 404 にせず既定値を返す</b>。運営の発行者設定は「1 行しか無い設定画面」で
     * あり、初回アクセス時に 404 を返すと運営コンソールが開かない。加えて
     * {@code receipt_issuer_settings.issuer_name} はアプリ層暗号化列であるため、
     * Flyway の SQL で初期行を投入すると<b>復号できない平文が入る</b>。したがって初期行は
     * マイグレーションでは作らず、PUT による最初の保存で作る。</p>
     */
    public IssuerSettingsResponse getPlatformSettings(Long actorUserId) {
        accessControlService.checkAdminOrAboveIncludingPlatform(
                actorUserId, ReceiptScopes.PLATFORM_SCOPE_ID, AccessControlService.PLATFORM_SCOPE_TYPE);

        boolean exists = issuerSettingsRepository
                .findByScopeTypeAndScopeId(ReceiptScopeType.PLATFORM, ReceiptScopes.PLATFORM_SCOPE_ID)
                .isPresent();
        if (exists) {
            return issuerSettingsService.getSettings(
                    ReceiptScopeType.PLATFORM, ReceiptScopes.PLATFORM_SCOPE_ID, actorUserId);
        }
        return emptyPlatformSettings();
    }

    /**
     * PLATFORM 発行者設定を更新する（未作成なら作成する UPSERT）。
     */
    @Transactional
    public IssuerSettingsResponse updatePlatformSettings(Long actorUserId,
                                                         UpdateIssuerSettingsRequest request) {
        accessControlService.checkAdminOrAboveIncludingPlatform(
                actorUserId, ReceiptScopes.PLATFORM_SCOPE_ID, AccessControlService.PLATFORM_SCOPE_TYPE);
        return issuerSettingsService.upsertSettings(
                ReceiptScopeType.PLATFORM, ReceiptScopes.PLATFORM_SCOPE_ID, actorUserId, request);
    }

    /**
     * 運営領収書の一覧を返す。<b>0 件でも 404 にせず空配列を返す</b>。
     *
     * @param includeVoided 無効化済みを含めるか（既定 false）
     */
    public PageResponse<PlatformReceiptSummaryResponse> listReceipts(
            Long actorUserId, boolean includeVoided, int page, int size) {
        accessControlService.checkAdminOrAboveIncludingPlatform(
                actorUserId, ReceiptScopes.PLATFORM_SCOPE_ID, AccessControlService.PLATFORM_SCOPE_TYPE);

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<ReceiptEntity> receipts = includeVoided
                ? receiptRepository.findByScopeTypeAndScopeIdOrderByIssuedAtDesc(
                        ReceiptScopeType.PLATFORM, ReceiptScopes.PLATFORM_SCOPE_ID, pageable)
                : receiptRepository.findByScopeTypeAndScopeIdAndVoidedAtIsNullOrderByIssuedAtDesc(
                        ReceiptScopeType.PLATFORM, ReceiptScopes.PLATFORM_SCOPE_ID, pageable);

        return PageResponse.of(receipts, PlatformReceiptService::toSummary);
    }

    private static PlatformReceiptSummaryResponse toSummary(ReceiptEntity r) {
        return new PlatformReceiptSummaryResponse(
                r.getId(),
                r.getReceiptNumber(),
                r.getSourceType() == null ? null : r.getSourceType().name(),
                r.getSourceRef(),
                r.getRecipientName(),
                r.getAmount(),
                r.getTaxAmount(),
                r.getAmountExclTax(),
                r.getIsQualifiedInvoice(),
                r.getInvoiceRegistrationNumber(),
                r.getIssuedAt(),
                r.getVoidedAt(),
                r.getPdfStatus() == null ? null : r.getPdfStatus().name());
    }

    /** 未登録時に返す空の設定（スコープだけが確定している状態）。 */
    private static IssuerSettingsResponse emptyPlatformSettings() {
        return new IssuerSettingsResponse(
                null,
                ReceiptScopeType.PLATFORM.name(),
                ReceiptScopes.PLATFORM_SCOPE_ID,
                null, null, null, null,
                Boolean.FALSE,
                null, null, null, null, null, null, null,
                1,
                null,
                4,
                Boolean.TRUE,
                null, null);
    }
}
