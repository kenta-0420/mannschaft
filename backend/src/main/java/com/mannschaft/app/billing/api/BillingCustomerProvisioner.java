package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code billing_customers} の引き上げ（provision）を<b>独立トランザクション</b>で行う。
 *
 * <h2>なぜ別クラスに切り出すか</h2>
 * <p>{@code REQUIRES_NEW} は Spring のプロキシを経由して初めて効く。同一クラス内の
 * 自己呼び出しではプロキシを通らず伝播設定が<b>黙って無視される</b>（＝呼び出し元の
 * トランザクションに参加してしまい、この仕組みが狙った効果を失う）。
 * 自己注入で回避する手もあるがプロキシ方式に依存して壊れやすいため、別 Bean にして
 * 「必ずプロキシを通る」ことを構造で保証する。</p>
 *
 * <h2>なぜ独立トランザクションでなければならないか</h2>
 * <p>呼び出し元の tx1 に参加したまま INSERT すると、{@code uk_bcu_scope}
 * （{@code scope_kind, scope_id} の UNIQUE）違反が<b>その tx1 を rollback-only にする</b>。
 * 例外を捕まえて既存行を返しても tx1 は既に死んでおり、後続の operation / pointer の
 * INSERT ごと commit 時に必ず失敗する。独立トランザクションなら巻き戻るのは
 * <b>引き上げだけ</b>で、tx1 は無傷のまま続行できる。</p>
 *
 * <p>もう一つの効能として、勝者の行が<b>その場で commit される</b>。tx1 に参加していると
 * 勝者の行は未コミットのままなので、敗者が読み直しても（READ_COMMITTED では）見えず、
 * 「INSERT は弾かれるのに読んでも無い」という詰みになる。独立 tx はこの詰みも同時に解く。</p>
 *
 * <p>tx1 がこの後に巻き戻っても引き上げた行は残るが、scope 単位で高々1行の冪等な行であり、
 * 次回の解決でそのまま再利用される（孤児にはならない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BillingCustomerProvisioner {

    /** {@code chk_bcu_ref_by_status}: ACTIVE は {@code psp_customer_ref} 非 NULL が条件。 */
    private static final String STATUS_ACTIVE = "ACTIVE";
    /** {@code chk_bcu_ref_by_status}: PROVISIONING は {@code psp_customer_ref} が NULL であること。 */
    private static final String STATUS_PROVISIONING = "PROVISIONING";

    private final BillingCustomerJpaRepository billingCustomerJpaRepository;
    private final Clock clock;

    /**
     * scope の {@code billing_customers} を新規作成する。
     *
     * <p>UNIQUE 違反は握り潰さず呼び出し元へ伝える（呼び出し元が「先客が居る」と解釈して読み直す）。
     * 本メソッドのトランザクションだけが巻き戻る。</p>
     *
     * @param scopeKind      USER / TEAM / ORG
     * @param scopeId        scope の ID
     * @param organizationId テナント（{@code null} 可）
     * @param pspCustomerRef 契約に焼き付いている Stripe Customer ID（{@code null} 可）
     * @return 作成した {@code billing_customers.id}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID provision(
            EntitlementScopeKind scopeKind, Long scopeId, Long organizationId, String pspCustomerRef) {
        Instant now = clock.instant();
        BillingCustomerEntity created = BillingCustomerEntity.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .organizationId(organizationId)
                .pspCustomerRef(pspCustomerRef)
                .status(pspCustomerRef == null ? STATUS_PROVISIONING : STATUS_ACTIVE)
                .provisionAttempts(0)
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        billingCustomerJpaRepository.saveAndFlush(created);
        log.info("PR6a: billing_customers を引き上げた（F20.1 決済フロー由来の契約は billing_customer_id を"
                + "持たないため）: scopeKind={}, scopeId={}", scopeKind, scopeId);
        return created.getId();
    }
}
