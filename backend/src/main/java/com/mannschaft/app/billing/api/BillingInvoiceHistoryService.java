package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.BillingInvoiceAdjustmentResponse;
import com.mannschaft.app.billing.api.dto.BillingInvoiceDetailResponse;
import com.mannschaft.app.billing.api.dto.BillingInvoiceIssuerResponse;
import com.mannschaft.app.billing.api.dto.BillingInvoiceLineResponse;
import com.mannschaft.app.billing.api.dto.BillingInvoiceSummaryResponse;
import com.mannschaft.app.billing.api.dto.BillingInvoiceTaxBreakdownResponse;
import com.mannschaft.app.billing.api.dto.BillingManageableScopeListResponse;
import com.mannschaft.app.billing.api.dto.BillingManageableScopeResponse;
import com.mannschaft.app.billing.api.dto.BillingMoneyResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * F20.1 Billing Center — 課金履歴の参照（AC-44〜AC-60）。
 *
 * <h2>認可</h2>
 * <p>すべての入口で {@link BillingAccessGuard} を通す。USER は本人のみ、TEAM/ORG は ADMIN 又は
 * 課金権限を明示付与された DEPUTY_ADMIN。<b>SYSTEM_ADMIN の権限文字列による短絡許可は無い</b>
 * （消費者向け API から全 scope の請求書が読めてしまうため）。</p>
 *
 * <h2>存在秘匿（IDOR）</h2>
 * <p>明細は「不在の id」と「他 scope の id」を <b>同じ 404 + {@code ENTITLEMENT_018}</b> で返す。
 * 他 scope を 403 にすると「その id は存在する」という存在オラクルが残るため。
 * 一方、一覧は要求 scope そのものを要求者が指定するので秘匿すべき対象が無く、
 * 403（{@code ENTITLEMENT_005}）で明示的に拒否する。</p>
 *
 * <h2>SQL 本数（AC-57/AC-58）</h2>
 * <p>一覧は keyset 1本のみ。明細は invoice / lines / adjustments の3本を <b>子の有無に依らず</b>
 * 常に発行する（0 件経路で問合せを省くと本数が検体依存になり、性能の回帰を測れなくなる）。</p>
 *
 * <h2>監査（AC-60）</h2>
 * <p>明細閲覧で {@code BILLING_INVOICE_VIEWED} を記録する。metadata には object ref（invoice id）と
 * scope だけを載せ、<b>URL・請求先住所の全文・PSP payload は載せない</b>。
 * 記録は {@code AuditLogService#recordSync} で同一要求スレッドから同期に行う
 * （{@code record} は {@code @Async} のため「閲覧は成功したのに監査がまだ無い」時間窓が開く）。</p>
 */
@Service
@RequiredArgsConstructor
public class BillingInvoiceHistoryService {

    /** 一覧 size の下限・上限（AC-49）。 */
    static final int MIN_PAGE_SIZE = 1;
    static final int MAX_PAGE_SIZE = 100;

    /** カーソル無しのときにバインドする番兵（述語は hasCursor=0 で無効化される）。 */
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private final BillingInvoiceHistoryJpaRepository invoiceRepository;
    private final BillingInvoiceLineJpaRepository lineRepository;
    private final BillingInvoiceAdjustmentJpaRepository adjustmentRepository;
    private final BillingAccessGuard billingAccessGuard;
    private final BillingManageableScopeRepository manageableScopeRepository;
    private final AuditLogService auditLogService;

    // ============================================================
    // 一覧（AC-45〜AC-51）
    // ============================================================

    /**
     * scope の請求書を新しい順に1ページ返す。
     *
     * @param actorId   要求者
     * @param scopeKind 対象 scope 種別
     * @param scopeId   対象 scope
     * @param size      ページサイズ（1〜100・Controller 側で検証済み）
     * @param rawCursor 前ページの {@code nextCursor}（先頭ページは null）
     */
    @Transactional(readOnly = true)
    public CursorPagedResponse<BillingInvoiceSummaryResponse> list(
            long actorId,
            EntitlementScopeKind scopeKind,
            Long scopeId,
            int size,
            String rawCursor) {
        requireManage(actorId, scopeKind, scopeId);

        BillingInvoiceCursor cursor = BillingInvoiceCursor.decode(rawCursor);
        // hasNext を「次ページの存在」で判定するため 1 件だけ多く読む。
        List<BillingInvoiceEntity> rows = invoiceRepository.findPage(
                scopeKind,
                scopeId,
                cursor == null ? 0 : 1,
                cursor == null ? 0 : cursor.nullFlag(),
                cursor == null ? Instant.EPOCH : cursor.periodEndOrSentinel(),
                cursor == null ? ZERO_UUID : cursor.id(),
                PageRequest.of(0, size + 1));

        boolean hasNext = rows.size() > size;
        List<BillingInvoiceEntity> page = hasNext ? rows.subList(0, size) : rows;

        String nextCursor = null;
        if (hasNext) {
            BillingInvoiceEntity last = page.get(page.size() - 1);
            nextCursor = BillingInvoiceCursor.of(last.getPeriodEnd(), last.getId()).encode();
        }

        List<BillingInvoiceSummaryResponse> items = new ArrayList<>(page.size());
        for (BillingInvoiceEntity invoice : page) {
            items.add(toSummary(invoice));
        }
        return CursorPagedResponse.of(
                items, new CursorPagedResponse.CursorMeta(nextCursor, hasNext, size));
    }

    // ============================================================
    // 明細（AC-52〜AC-54・AC-60）
    // ============================================================

    /**
     * 請求書の明細を返す。不在・他 scope はいずれも 404（{@code ENTITLEMENT_018}）。
     *
     * <p><b>意図的に {@code @Transactional} を付けない</b>。理由は2つある。
     * (a) {@code readOnly = true} で括ると MySQL の read-only トランザクション下で
     * 監査ログの INSERT が失敗しうる（表示できたのに監査だけ落ちる、を作らない）。
     * (b) 監査は auth ドメインの表への書き込みであり、billing の読み取りと同一
     * トランザクションに括るとドメインを跨ぐ境界を新設することになる。
     * 読み取りは3本とも独立した単純 SELECT で、跨いで整合を取るべき不変条件は無い。</p>
     */
    public BillingInvoiceDetailResponse detail(long actorId, UUID invoiceId) {
        BillingInvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .filter(i -> i.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.INVOICE_NOT_FOUND));

        if (!billingAccessGuard.canManageByActorId(
                actorId, invoice.getScopeKind(), invoice.getScopeId())) {
            // 403 にすると「この id は存在する」と教えることになる。不在と同一の応答へ畳む。
            throw new BusinessException(EntitlementErrorCode.INVOICE_NOT_FOUND);
        }

        // 子の有無に依らず常に2本引く（0 件で省くと SQL 本数が検体依存になる・AC-57）。
        List<BillingInvoiceLineEntity> lines = lineRepository.findByInvoiceId(invoiceId);
        List<BillingInvoiceAdjustmentEntity> adjustments =
                adjustmentRepository.findByInvoiceId(invoiceId);

        recordViewAudit(actorId, invoice);

        return toDetail(invoice, lines, adjustments);
    }

    // ============================================================
    // scope 列挙（AC-55/AC-56）
    // ============================================================

    /**
     * 要求者が課金を管理できる scope を列挙する。
     *
     * <p>列挙結果は必ず {@link BillingAccessGuard} で再検証する。列挙 SQL が認可の
     * 唯一の真実源になると、片方だけ条件が緩んだときに静かに IDOR になる。</p>
     */
    @Transactional(readOnly = true)
    public BillingManageableScopeListResponse manageableScopes(long actorId) {
        List<BillingManageableScopeResponse> candidates = new ArrayList<>();

        // 本人の USER scope は常に管理できる（表示名は FE が「自分」等に解決するため持たない）。
        candidates.add(scope(EntitlementScopeKind.USER, actorId, null));

        for (BillingManageableScopeRepository.ManageableScopeRow row
                : manageableScopeRepository.findManageableTeams(
                        actorId, BillingAccessGuard.TEAM_PERMISSION)) {
            candidates.add(scope(EntitlementScopeKind.TEAM, row.id(), row.name()));
        }
        for (BillingManageableScopeRepository.ManageableScopeRow row
                : manageableScopeRepository.findManageableOrganizations(
                        actorId, BillingAccessGuard.ORGANIZATION_PERMISSION)) {
            candidates.add(scope(EntitlementScopeKind.ORG, row.id(), row.name()));
        }

        List<BillingManageableScopeResponse> verified = candidates.stream()
                .filter(item -> item.getId() != null)
                .filter(item -> billingAccessGuard.canManageByActorId(
                        actorId, EntitlementScopeKind.valueOf(item.getKind()), item.getId()))
                .sorted(Comparator.comparing(BillingManageableScopeResponse::getKind)
                        .thenComparing(BillingManageableScopeResponse::getId))
                .toList();

        return BillingManageableScopeListResponse.builder().items(verified).build();
    }

    // ============================================================
    // 内部
    // ============================================================

    private void requireManage(long actorId, EntitlementScopeKind scopeKind, Long scopeId) {
        if (!billingAccessGuard.canManageByActorId(actorId, scopeKind, scopeId)) {
            throw new BusinessException(EntitlementErrorCode.SCOPE_FORBIDDEN);
        }
    }

    private BillingManageableScopeResponse scope(EntitlementScopeKind kind, Long id, String name) {
        return BillingManageableScopeResponse.builder()
                .kind(kind.name())
                .id(id)
                .name(name)
                .manage(true)
                .build();
    }

    private void recordViewAudit(long actorId, BillingInvoiceEntity invoice) {
        // URL・住所全文・payload を含めない。載せるのは object ref と scope だけ（AC-60）。
        String metadata = "{\"invoiceId\":\"" + invoice.getId()
                + "\",\"scopeKind\":\"" + invoice.getScopeKind().name()
                + "\",\"scopeId\":" + invoice.getScopeId() + "}";

        Long teamId = invoice.getScopeKind() == EntitlementScopeKind.TEAM
                ? invoice.getScopeId() : null;
        Long organizationId = invoice.getScopeKind() == EntitlementScopeKind.ORG
                ? invoice.getScopeId() : invoice.getOrganizationId();

        // auth ドメインの表へは Service 経由でのみ書く（repository / entity を直接触ると
        // D-1 / D-5 のドメイン越境ガードが拒否する）。recordSync は呼び出しスレッドで
        // 同期に書くため、200 を返した時点で監査行が存在することを保証できる。
        auditLogService.recordSync(
                AuditEventType.BILLING_INVOICE_VIEWED.name(),
                actorId,
                null,
                teamId,
                organizationId,
                null,
                null,
                null,
                metadata);
    }

    private BillingMoneyResponse money(Long amount, String currency) {
        return BillingMoneyResponse.builder()
                .amount(amount == null ? 0L : amount)
                .currency(currency)
                .build();
    }

    private BillingInvoiceSummaryResponse toSummary(BillingInvoiceEntity invoice) {
        String currency = invoice.getCurrency();
        return BillingInvoiceSummaryResponse.builder()
                .id(invoice.getId().toString())
                .scopeKind(invoice.getScopeKind().name())
                .scopeId(invoice.getScopeId())
                .status(invoice.getStatus())
                .billingReason(invoice.getBillingReason())
                .periodStart(invoice.getPeriodStart())
                .periodEnd(invoice.getPeriodEnd())
                .subtotal(money(invoice.getSubtotalAmount(), currency))
                .discount(money(invoice.getDiscountAmount(), currency))
                .tax(money(invoice.getTaxAmount(), currency))
                .total(money(invoice.getTotalAmount(), currency))
                .finalizedAt(invoice.getFinalizedAt())
                .paidAt(invoice.getPaidAt())
                .build();
    }

    private BillingInvoiceDetailResponse toDetail(
            BillingInvoiceEntity invoice,
            List<BillingInvoiceLineEntity> lines,
            List<BillingInvoiceAdjustmentEntity> adjustments) {
        String currency = invoice.getCurrency();
        return BillingInvoiceDetailResponse.builder()
                .id(invoice.getId().toString())
                .scopeKind(invoice.getScopeKind().name())
                .scopeId(invoice.getScopeId())
                .status(invoice.getStatus())
                .billingReason(invoice.getBillingReason())
                .periodStart(invoice.getPeriodStart())
                .periodEnd(invoice.getPeriodEnd())
                .issuer(BillingInvoiceIssuerResponse.builder()
                        .name(invoice.getIssuerNameSnapshot())
                        .build())
                .billingName(invoice.getBillingNameSnapshot())
                .subtotal(money(invoice.getSubtotalAmount(), currency))
                .discount(money(invoice.getDiscountAmount(), currency))
                .tax(money(invoice.getTaxAmount(), currency))
                .total(money(invoice.getTotalAmount(), currency))
                .lines(lines.stream()
                        .sorted(Comparator.comparing(
                                BillingInvoiceLineEntity::getPspLineRef,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(this::toLine)
                        .toList())
                .adjustments(adjustments.stream()
                        .sorted(Comparator.comparing(
                                BillingInvoiceAdjustmentEntity::getEffectiveAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(this::toAdjustment)
                        .toList())
                .finalizedAt(invoice.getFinalizedAt())
                .paidAt(invoice.getPaidAt())
                .voidedAt(invoice.getVoidedAt())
                .build();
    }

    private BillingInvoiceLineResponse toLine(BillingInvoiceLineEntity line) {
        return BillingInvoiceLineResponse.builder()
                .id(line.getId().toString())
                .description(line.getDescriptionSnapshot())
                .quantity(line.getQuantity())
                .amountExcludingTax(line.getAmountExcludingTax())
                .discountAmount(line.getDiscountAmount())
                .amountIncludingTax(line.getAmountIncludingTax())
                .taxes(List.of(BillingInvoiceTaxBreakdownResponse.builder()
                        .taxName(line.getTaxNameSnapshot())
                        .taxRateBasisPoints(line.getTaxRateBasisPoints())
                        .taxAmount(line.getTaxAmount() == null ? 0L : line.getTaxAmount())
                        .includedInPrice(line.getIncludedInPrice())
                        .build()))
                .periodStart(line.getPeriodStart())
                .periodEnd(line.getPeriodEnd())
                .build();
    }

    private BillingInvoiceAdjustmentResponse toAdjustment(BillingInvoiceAdjustmentEntity a) {
        return BillingInvoiceAdjustmentResponse.builder()
                .id(a.getId().toString())
                .kind(a.getKind())
                .status(a.getStatus())
                .amount(money(a.getAmount(), a.getCurrency()))
                .reason(a.getReason())
                .effectiveAt(a.getEffectiveAt())
                .build();
    }
}
