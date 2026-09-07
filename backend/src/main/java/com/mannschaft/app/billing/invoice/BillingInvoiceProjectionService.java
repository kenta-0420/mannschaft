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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** 部分 payload の全件取得と、同一秒・同一状態の同着裁定に使う（いずれも稀な経路）。 */
    private final StripeInvoiceRetriever invoiceRetriever;

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
    public void project(InvoiceView incoming, BillingInvoiceOwner owner, String eventType, long eventCreatedEpoch) {
        // 明細に依存しないヘッダ検査（通貨・符号・金額恒等式）は repository に触れる前に済ませる。
        // ここを後ろへ動かすと「非 JPY を永続化を試みる前に拒否する」（AC-34）が崩れる。
        validateHeader(incoming);

        Instant eventInstant = Instant.ofEpochSecond(eventCreatedEpoch);
        Optional<BillingInvoiceEntity> existing = invoiceRepository.findByPspInvoiceRef(incoming.id());

        InvoiceView resolved = incoming;
        if (existing.isPresent() && existing.get().getUpdatedAt() != null) {
            Instant appliedAt = existing.get().getUpdatedAt();
            if (appliedAt.isAfter(eventInstant)) {
                log.info("F20.1 PR5: 投影より古い event のため適用しない（単調更新）: invoice={}, eventCreated={}",
                        incoming.id(), eventInstant);
                return;
            }
            if (appliedAt.equals(eventInstant)) {
                int diff = statusRank(mapStatus(incoming.status())) - statusRank(existing.get().getStatus());
                if (diff < 0) {
                    log.info("F20.1 PR5: 同一秒で状態が後退する event のため適用しない: invoice={}, eventCreated={}",
                            incoming.id(), eventInstant);
                    return;
                }
                if (diff == 0) {
                    resolved = authoritativeOnTie(incoming);
                }
            }
        }

        // 以降は「全明細が揃い、検証を通った invoice」だけを扱う。
        final InvoiceView invoice = withCompleteLines(resolved);
        // 明細に依存する検査（税の裏付け・line 税込合計 == total）は、全明細が揃ってから行う。
        validateLines(invoice);
        String incomingStatus = mapStatus(invoice.status());

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
        entity.setStatus(incomingStatus);
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

    /**
     * {@code billing_invoice_lines} を UNIQUE(invoice_id, psp_line_ref) を鍵に<b>最新値へ揃える</b>（AC-14）。
     *
     * <p><b>なぜ「存在したら skip」ではないのか</b>: Stripe の請求書は draft → finalized の間に
     * 金額・税・説明・数量が変わりうる。{@code invoice.created} で積んだ行を後続の
     * {@code invoice.updated} / {@code invoice.finalized} で更新しないと、ヘッダ
     * （{@code billing_invoices} の subtotal / tax / total）だけが新しくなり、明細合計と
     * 食い違ったまま固定される。請求書 PDF（F08.12）はこの明細を正本にするため、
     * 誤った金額の請求書を出すことになる。</p>
     *
     * <p><b>単調更新との整合（AC-9）</b>: 「古いイベントを適用しない」判定は
     * {@link #project} が投影行の {@code updated_at} と {@code event.created} を比べて
     * 既に一箇所で行っており、古いイベントはここへ到達しない。すなわち本メソッドが走るのは
     * 「これまでに適用したどのイベントよりも新しい（または同時刻の）イベント」だけであり、
     * 明細をそのイベントの内容で置き換えても、確定済み請求書が古い値へ巻き戻ることはない。
     * 単調性の判定をヘッダと明細で二重に持たない（判定正本は 1 箇所）。</p>
     *
     * <p><b>消えた行は消す。ただし全明細を受け取ったときだけ</b>: draft 段階の line が finalized で
     * 取り下げられることがあり、残置すると明細合計とヘッダ total が一致しなくなる。そこで今回の
     * payload に無い行は削除する——が、これは payload が<b>請求書の全明細</b>であるときにしか
     * 正しくない。Stripe の invoice webhook に載る {@code lines.data} は件数上限で切られることがあり
     * （{@code lines.has_more=true}）、切られた頁を全明細と誤認して削除すると、載らなかった明細が
     * 恒久的に消え、F08.12 の請求書 PDF も欠けた明細で出る。
     * よって削除は {@link InvoiceView#linesComplete()} が true のときに限る（fail-safe）。</p>
     *
     * <p><b>完全性は呼び出し元が保証する</b>: {@link #project} は
     * {@link StripeInvoiceRetriever} で全明細を取り直してからここへ来るため、実運用でこの条件が
     * false になることはない（取得できなければ投影自体を見送る）。それでも条件を残すのは、
     * 将来この経路が増えたときに「不完全な payload で全置換する」誤りを構造で止めるためである。</p>
     */
    private void projectLines(BillingInvoiceEntity invoiceEntity, InvoiceView invoice) {
        Map<String, BillingInvoiceLineEntity> existingByRef = new LinkedHashMap<>();
        for (BillingInvoiceLineEntity row : invoiceLineRepository.findByInvoiceId(invoiceEntity.getId())) {
            existingByRef.put(row.getPspLineRef(), row);
        }

        List<BillingInvoiceLineEntity> toSave = new ArrayList<>();
        for (InvoiceLineView line : invoice.lines()) {
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

            BillingInvoiceLineEntity entity = existingByRef.remove(line.id());
            if (entity == null) {
                entity = BillingInvoiceLineEntity.builder()
                        .invoiceId(invoiceEntity.getId())
                        .pspLineRef(line.id())
                        .createdAt(Instant.now())
                        .build();
            }
            entity.setOrganizationId(invoiceEntity.getOrganizationId());
            entity.setStripePriceRef(line.priceRef());
            entity.setDescriptionSnapshot(line.description());
            entity.setQuantity(line.quantity());
            entity.setAmountExcludingTax(excluding);
            entity.setDiscountAmount(discount);
            entity.setTaxNameSnapshot(line.taxName());
            entity.setTaxRateBasisPoints(line.taxRateBasisPoints());
            entity.setTaxAmount(line.taxAmount());
            entity.setIncludedInPrice(line.taxInclusive());
            entity.setAmountIncludingTax(including);
            entity.setPeriodStart(toInstant(line.periodStartEpochSec()));
            entity.setPeriodEnd(toInstant(line.periodEndEpochSec()));
            toSave.add(entity);
        }

        // payload に現れなかった既存行（＝取り下げられた line）を落とす。
        // ただし payload が全明細だと確認できたときだけ。切られた頁を全明細と誤認して消すと、
        // 載らなかった明細が恒久的に失われる（後述の Javadoc 参照）。
        if (!existingByRef.isEmpty() && invoice.linesComplete()) {
            invoiceLineRepository.deleteAll(existingByRef.values());
        }
        if (!toSave.isEmpty()) {
            // flush して DB 制約違反をこの場で顕在化させる（握り潰さず呼び出し元の境界に伝える）。
            invoiceLineRepository.saveAllAndFlush(toSave);
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
        validateHeader(invoice);
        validateLines(invoice);
    }

    /** 明細に依存しないヘッダ検査。repository に触れる前に呼ぶ。 */
    private void validateHeader(InvoiceView invoice) {
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
    }

    /**
     * 明細に依存する検査。<b>全明細が揃ってから</b>呼ぶ。
     *
     * <p>{@code line の税込合計 == total} は、部分 payload（{@code lines.has_more=true}）では
     * 決して満たされない。Stripe は切られた頁でも {@code total} は請求書全体の値を返すためである。
     * したがってこの検査は {@link #withCompleteLines} を通した後にのみ意味を持つ。</p>
     */
    private void validateLines(InvoiceView invoice) {
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

    /**
     * 単調更新の判定正本（AC-9）— 補助である「同着の裁定」と「明細の完全化」。
     *
     * <p>基準は投影行の {@code updated_at}（＝直近に適用した event の発生時刻）。それより古ければ
     * 適用しない。<b>同一秒</b>は時刻では決められない——Stripe の {@code event.created} は秒精度で、
     * finalized と遅れて届いた draft が同じ秒を持ちうるためである。同一秒はまず
     * <b>請求書状態の順序</b>（DRAFT &lt; OPEN &lt; PAID/VOID/UNCOLLECTIBLE）で決め、後退する側を退ける。</p>
     *
     * <p><b>同一秒かつ同一状態</b>（例: 同じ秒に届いた 2 つの {@code invoice.updated}）は、
     * 時刻でも状態でも先後を決められない。ここで payload をそのまま信じると、古いほうが後に
     * 届いただけで金額・明細が巻き戻る。よって<b>どちらの payload も信じず</b>、
     * {@link StripeInvoiceRetriever} で「現在の Stripe 上の invoice」を取得してそれを正とする。
     * 取得できなければ確定させず投げる（fail-closed・Stripe の再送で再試行）。</p>
     *
     * <p><b>この経路が高頻度で走らない根拠</b>: 走る条件は「同一 invoice の 2 イベントが<b>同じ秒</b>に
     * 発生し、かつ<b>状態も同じ</b>」であり、通常のライフサイクル（created → finalized → paid）は
     * 状態が進むので rank で決着し、ここへ来ない。到達するのは同一秒に同一状態の更新が二重に起きた
     * ときだけで、1 請求書あたり多くとも数回、常態では 0 回である。したがって Stripe 呼び出しが
     * webhook 処理の定常コストになることはない。</p>
     */
    private InvoiceView authoritativeOnTie(InvoiceView incoming) {
        return invoiceRetriever.retrieve(incoming.id())
                .orElseThrow(() -> new IllegalStateException(
                        "同一秒・同一状態の event が衝突したが Stripe から invoice を取得できず、"
                                + "どちらが新しいか決められないため投影しません: invoice=" + incoming.id()));
    }

    /**
     * 明細が全件揃った invoice を返す（揃っていなければ Stripe から取り直す）。
     *
     * <p><b>なぜ必須か</b>: webhook の {@code lines.data} は件数上限で切られることがあり
     * （{@code lines.has_more=true}）、そのとき {@code subtotal/tax/total} は<b>請求書全体の値</b>の
     * ままである。したがって切られた明細だけでは {@link #validate} の「line の税込合計 == total」を
     * 決して満たさず、<b>明細が上限を超える請求書は一度も投影できない</b>（fail-closed なのでデータは
     * 壊れないが、履歴に永久に出ず webhook は失敗し続ける）。全件を取りに行くのが唯一の解である。</p>
     *
     * <p>取得できない場合は投げる。部分 payload で代用すると明細が欠けた請求書が確定してしまう。</p>
     */
    private InvoiceView withCompleteLines(InvoiceView invoice) {
        if (invoice.linesComplete()) {
            return invoice;
        }
        return invoiceRetriever.retrieve(invoice.id())
                .filter(InvoiceView::linesComplete)
                .orElseThrow(() -> new IllegalStateException(
                        "明細が件数上限で切られた payload に対し Stripe から全明細を取得できなかったため"
                                + "投影しません: invoice=" + invoice.id()));
    }

    /**
     * 請求書状態の単調な順序。同一 {@code event.created} の順序付けにのみ使う。
     *
     * <p>DRAFT(0) → OPEN(1) → PAID / VOID / UNCOLLECTIBLE(2)。終端状態どうしは同順で、
     * 互いに退けない（同一秒に PAID と VOID の両方が来る検体は Stripe 側で起きない）。</p>
     */
    private static int statusRank(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case "DRAFT" -> 0;
            case "OPEN" -> 1;
            default -> 2;
        };
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
