package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.BillingContractEntity;
import com.mannschaft.app.billing.BillingContractRepository;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.billing.api.BillingCustomerJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceJpaRepository;
import com.mannschaft.app.billing.api.BillingInvoiceLineEntity;
import com.mannschaft.app.billing.api.BillingInvoiceLineJpaRepository;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceLineView;
import com.mannschaft.app.billing.invoice.StripeBillingObjectView.InvoiceView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * F20.1 PR5: Stripe invoice を {@code billing_invoices} / {@code billing_invoice_lines} へ投影する。
 *
 * <p><b>fail-closed</b>: 通貨・金額恒等式・税の裏付け・line 合計との一致を
 * <b>永続化を試みる前に</b>すべて検査し、破れていれば
 * {@link BillingInvoiceProjectionRejectedException} を投げて投影を確定しない（AC-5 / 34 / 37 / 39）。</p>
 *
 * <p><b>再丸めしない</b>: JPY は最小通貨単位＝円で小数を持たない。Stripe が出した line amount /
 * tax_amount をそのまま保存し、こちら側で単価×数量の再計算や四捨五入を行わない（AC-6）。
 * 税込・税抜の別（{@code tax_amounts[].inclusive}）だけを見て、税抜額と税込額を導出する。</p>
 *
 * <p><b>単調更新</b>: {@code event.created} が既存投影の {@code updated_at} より古いイベントは
 * 適用しない。Stripe の順不同再送で PAID を OPEN へ巻き戻さないためである（AC-9）。
 * 投影行の {@code updated_at} には「適用したイベントの発生時刻」を入れることで、
 * 新規列を足さずに単調性を判定できるようにしている。</p>
 *
 * <p><b>トランザクション境界</b>: 本サービスは自前で {@code @Transactional} を宣言しない。
 * 呼び出し元（webhook 1 イベントの処理）のトランザクションに必ず参加させ、
 * 「invoice 投影と契約期間延長が一体に成否する」ことを担保するためである（AC-20 / AC-26）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingInvoiceProjectionService {

    /** 投影を許す唯一の通貨（設計書 05 §8・非 JPY は fail-closed）。 */
    private static final String ALLOWED_CURRENCY = "JPY";

    /** 税率の上限（100% = 10000 basis points。DDL の {@code chk_bil_tax} と一致）。 */
    private static final int MAX_TAX_BASIS_POINTS = 10_000;

    private final StripeBillingPayloadParser parser;
    private final BillingCustomerJpaRepository billingCustomerRepository;
    private final BillingContractRepository billingContractRepository;
    private final BillingInvoiceJpaRepository invoiceRepository;
    private final BillingInvoiceLineJpaRepository invoiceLineRepository;
    private final StripeSubscriptionMetadataVerifier subscriptionMetadataVerifier;

    /**
     * 請求書の発行者名（プラットフォーム＝運営）。請求先（利用者）の氏名ではない（AC-40）。
     * F08.12 の請求書 PDF はこの snapshot をそのまま発行者欄に使う。
     */
    @Value("${mannschaft.billing.issuer-name:Mannschaft 運営事務局}")
    private String issuerName;

    /**
     * payload の {@code invoice.customer} から scope 所有の Customer を解決する。
     *
     * @return 所有者。scope 所有の Customer に一致しなければ {@link Optional#empty()}（＝billing 所有ではない）
     */
    public Optional<BillingInvoiceOwner> resolveOwner(String payload) {
        return parser.parseInvoice(payload).flatMap(this::resolveOwner);
    }

    /** {@link InvoiceView} から所有者を解決する（payload を二重パースしないための版）。 */
    public Optional<BillingInvoiceOwner> resolveOwner(InvoiceView invoice) {
        if (invoice.customerRef() == null) {
            return Optional.empty();
        }
        Optional<BillingCustomerEntity> customer =
                billingCustomerRepository.findByPspCustomerRefAndDeletedAtIsNull(invoice.customerRef());
        if (customer.isEmpty()) {
            return Optional.empty();
        }
        BillingCustomerEntity c = customer.get();

        // 契約は subscription ref から引く。ただし「同じ scope の契約であること」を必ず確かめる
        // （他 scope の契約に自 scope の invoice をぶら下げない）。
        BillingContractEntity contract = invoice.subscriptionRef() == null ? null
                : billingContractRepository
                        .findByPspSubscriptionRefAndDeletedAtIsNull(invoice.subscriptionRef())
                        .filter(ct -> sameScope(ct, c))
                        .orElse(null);

        // AC-8: DB 逆引きが外れたときは Stripe の Subscription metadata を厳密照合してから紐付ける。
        // 「subscription ref が DB に無い＝無関係」と決めつけると、順不同再送で契約行に ref が
        // まだ焼き付いていない invoice を取りこぼす。
        if (contract == null && invoice.subscriptionRef() != null) {
            contract = subscriptionMetadataVerifier
                    .resolveBillingContractId(invoice.subscriptionRef())
                    .flatMap(billingContractRepository::findByIdAndDeletedAtIsNull)
                    .filter(ct -> sameScope(ct, c))
                    .orElse(null);
        }

        return Optional.of(new BillingInvoiceOwner(
                c.getId(),
                contract == null ? null : contract.getId(),
                c.getScopeKind(),
                c.getScopeId(),
                c.getOrganizationId()));
    }

    /** 契約と Customer が同一 scope に属するか（他 scope の契約へぶら下げない）。 */
    private boolean sameScope(BillingContractEntity contract, BillingCustomerEntity customer) {
        return contract.getScopeKind() == customer.getScopeKind()
                && java.util.Objects.equals(contract.getScopeId(), customer.getScopeId());
    }

    /** payload から invoice を読む（呼び出し元の所有判定用）。 */
    public Optional<InvoiceView> readInvoice(String payload) {
        return parser.parseInvoice(payload);
    }

    /**
     * invoice を投影する（ヘッダ＋明細）。
     *
     * @param invoice           Stripe invoice
     * @param owner             scope 所有者
     * @param eventType         イベント種別（{@code invoice.finalized} 等・時刻列の決定に使う）
     * @param eventCreatedEpoch {@code event.created}（単調更新の基準）
     * @throws BillingInvoiceProjectionRejectedException 恒久拒否（fail-closed）
     */
    public void project(InvoiceView invoice, BillingInvoiceOwner owner, String eventType, long eventCreatedEpoch) {
        validate(invoice);

        Instant eventInstant = Instant.ofEpochSecond(eventCreatedEpoch);
        Optional<BillingInvoiceEntity> existing = invoiceRepository.findByPspInvoiceRef(invoice.id());

        if (existing.isPresent() && existing.get().getUpdatedAt() != null
                && existing.get().getUpdatedAt().isAfter(eventInstant)) {
            log.info("F20.1 PR5: 投影より古い event のため適用しない（単調更新）: invoice={}, eventCreated={}",
                    invoice.id(), eventInstant);
            return;
        }

        BillingInvoiceEntity entity = existing.orElseGet(() -> BillingInvoiceEntity.builder()
                .pspInvoiceRef(invoice.id())
                .version(0L)
                .createdAt(eventInstant)
                .build());

        entity.setBillingCustomerId(owner.billingCustomerId());
        entity.setContractId(owner.contractId());
        entity.setOrganizationId(owner.organizationId());
        entity.setScopeKind(owner.scopeKind());
        entity.setScopeId(owner.scopeId());
        entity.setPspSubscriptionRef(invoice.subscriptionRef());
        entity.setBillingReason(invoice.billingReason() == null ? "unspecified" : invoice.billingReason());
        entity.setStatus(mapStatus(invoice.status()));
        entity.setPeriodStart(toInstant(invoice.periodStartEpochSec()));
        entity.setPeriodEnd(toInstant(invoice.periodEndEpochSec()));
        entity.setCurrency(ALLOWED_CURRENCY);
        entity.setSubtotalAmount(invoice.subtotal());
        entity.setDiscountAmount(invoice.discount());
        entity.setTaxAmount(invoice.tax());
        entity.setTotalAmount(invoice.total());

        // F08.12 受け渡し（AC-40/41）: 発行者は運営、請求先は投影時点の snapshot。
        entity.setIssuerNameSnapshot(issuerName);
        entity.setBillingNameSnapshot(invoice.customerName());
        entity.setBillingEmailSnapshot(invoice.customerEmail());
        entity.setBillingAddressSnapshot(invoice.customerAddressJson());

        applyLifecycleTimestamps(entity, eventType, invoice.status(), eventInstant);
        entity.setUpdatedAt(eventInstant);

        BillingInvoiceEntity saved = invoiceRepository.saveAndFlush(entity);
        projectLines(saved, invoice);
    }

    /** {@code billing_invoice_lines} を UNIQUE(invoice_id, psp_line_ref) で冪等に積む（AC-14）。 */
    private void projectLines(BillingInvoiceEntity invoiceEntity, InvoiceView invoice) {
        List<BillingInvoiceLineEntity> toInsert = new ArrayList<>();
        for (InvoiceLineView line : invoice.lines()) {
            if (invoiceLineRepository.findByInvoiceIdAndPspLineRef(invoiceEntity.getId(), line.id()).isPresent()) {
                continue;
            }
            long discount = line.discountAmount();
            long including;
            long excluding;
            if (line.taxInclusive()) {
                // Stripe の inclusive line: amount は税込。税抜は税額を差し引いて導く。
                including = line.amount() - discount;
                excluding = including - line.taxAmount();
            } else {
                // exclusive line: amount は税抜（割引前）。税込は割引後に税を足したもの。
                excluding = line.amount();
                including = line.amount() - discount + line.taxAmount();
            }
            toInsert.add(BillingInvoiceLineEntity.builder()
                    .invoiceId(invoiceEntity.getId())
                    .organizationId(invoiceEntity.getOrganizationId())
                    .stripePriceRef(line.priceRef())
                    .pspLineRef(line.id())
                    .descriptionSnapshot(line.description())
                    .quantity(line.quantity())
                    .amountExcludingTax(excluding)
                    .discountAmount(discount)
                    .taxNameSnapshot(line.taxName())
                    .taxRateBasisPoints(line.taxRateBasisPoints())
                    .taxAmount(line.taxAmount())
                    .includedInPrice(line.taxInclusive())
                    .amountIncludingTax(including)
                    .periodStart(toInstant(line.periodStartEpochSec()))
                    .periodEnd(toInstant(line.periodEndEpochSec()))
                    .createdAt(Instant.now())
                    .build());
        }
        if (!toInsert.isEmpty()) {
            // flush して DB 制約違反をこの場で顕在化させる（握り潰さず呼び出し元の境界に伝える）。
            invoiceLineRepository.saveAllAndFlush(toInsert);
        }
    }

    // ───────────── fail-closed 検証 ─────────────

    /**
     * 投影してよい検体かを永続化前に検査する。
     *
     * <p>ここを DB の CHECK 制約に任せてはならない。DB で落とすと「投影を試みてから失敗する」ことになり、
     * 同一イベントで一体に成立させるべき契約遷移まで巻き添えで巻き戻る（設計書 05 §8）。</p>
     */
    void validate(InvoiceView invoice) {
        if (invoice.currency() == null || !ALLOWED_CURRENCY.equalsIgnoreCase(invoice.currency())) {
            throw reject("非 JPY の invoice は投影しない: invoice=%s, currency=%s"
                    .formatted(invoice.id(), invoice.currency()));
        }
        if (invoice.subtotal() < 0 || invoice.discount() < 0 || invoice.tax() < 0 || invoice.total() < 0) {
            throw reject("金額が負の invoice は投影しない: invoice=%s".formatted(invoice.id()));
        }
        if (invoice.subtotal() - invoice.discount() + invoice.tax() != invoice.total()) {
            throw reject(("金額恒等式が破れているため投影しない: invoice=%s, subtotal=%d, discount=%d, tax=%d, total=%d")
                    .formatted(invoice.id(), invoice.subtotal(), invoice.discount(), invoice.tax(), invoice.total()));
        }
        if (invoice.lines().isEmpty()) {
            throw reject("明細行の無い invoice は投影しない: invoice=%s".formatted(invoice.id()));
        }

        long lineIncludingSum = 0L;
        for (InvoiceLineView line : invoice.lines()) {
            if (line.id() == null || line.description() == null) {
                throw reject("line の識別子/名称が欠けているため投影しない: invoice=%s".formatted(invoice.id()));
            }
            if (line.amount() < 0 || line.discountAmount() < 0 || line.taxAmount() < 0) {
                throw reject("line の金額が負のため投影しない: invoice=%s, line=%s"
                        .formatted(invoice.id(), line.id()));
            }
            if (line.quantity() == null || line.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw reject("line の数量が 0 以下のため投影しない: invoice=%s, line=%s"
                        .formatted(invoice.id(), line.id()));
            }
            Integer bp = line.taxRateBasisPoints();
            if (bp != null && (bp < 0 || bp > MAX_TAX_BASIS_POINTS)) {
                throw reject("税率が範囲外のため投影しない: invoice=%s, line=%s, basisPoints=%d"
                        .formatted(invoice.id(), line.id(), bp));
            }
            if (line.taxAmount() > 0 && (bp == null || line.taxName() == null)) {
                throw reject("税額があるのに税率/税名の裏付けが無いため投影しない: invoice=%s, line=%s"
                        .formatted(invoice.id(), line.id()));
            }
            lineIncludingSum += line.taxInclusive()
                    ? line.amount() - line.discountAmount()
                    : line.amount() - line.discountAmount() + line.taxAmount();
        }
        if (lineIncludingSum != invoice.total()) {
            throw reject("line の税込合計が invoice total と一致しないため投影しない: invoice=%s, lineSum=%d, total=%d"
                    .formatted(invoice.id(), lineIncludingSum, invoice.total()));
        }
    }

    private BillingInvoiceProjectionRejectedException reject(String message) {
        log.warn("F20.1 PR5 fail-closed: {}", message);
        return new BillingInvoiceProjectionRejectedException(message);
    }

    // ───────────── 補助 ─────────────

    private void applyLifecycleTimestamps(BillingInvoiceEntity entity, String eventType,
                                          String stripeStatus, Instant eventInstant) {
        String status = stripeStatus == null ? "" : stripeStatus;
        if (entity.getFinalizedAt() == null
                && ("invoice.finalized".equals(eventType) || !"draft".equals(status))) {
            entity.setFinalizedAt(eventInstant);
        }
        if (entity.getPaidAt() == null && ("invoice.paid".equals(eventType) || "paid".equals(status))) {
            entity.setPaidAt(eventInstant);
        }
        if (entity.getVoidedAt() == null && ("invoice.voided".equals(eventType) || "void".equals(status))) {
            entity.setVoidedAt(eventInstant);
        }
    }

    /** Stripe の invoice status を DDL の CHECK（DRAFT/OPEN/PAID/UNCOLLECTIBLE/VOID）へ写す。 */
    private String mapStatus(String stripeStatus) {
        if (stripeStatus == null) {
            throw reject("invoice status が無いため投影しない");
        }
        return switch (stripeStatus) {
            case "draft" -> "DRAFT";
            case "open" -> "OPEN";
            case "paid" -> "PAID";
            case "uncollectible" -> "UNCOLLECTIBLE";
            case "void" -> "VOID";
            default -> throw reject("未知の invoice status のため投影しない: " + stripeStatus);
        };
    }

    private static Instant toInstant(Long epochSec) {
        return epochSec == null ? null : Instant.ofEpochSecond(epochSec);
    }
}
