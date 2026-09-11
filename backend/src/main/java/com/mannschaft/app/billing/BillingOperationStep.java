package com.mannschaft.app.billing;

/**
 * Billing Center PR6a: {@code billing_contract_operations.step}（VARCHAR(32) NOT NULL）に格納する
 * 値集合。
 *
 * <p><b>V196 の DDL にはこの列への CHECK 制約が無い</b>（自由記述の VARCHAR(32)）。
 * したがって値集合は DB 側では何も強制されない。実装側の遷移ガード（第5隊）が誤った値の書き込みを
 * 防ぐ唯一の防波堤になるため、ここで値集合を固定し、{@code kind} ごとの許容される遷移列を
 * 以下に明記する。新しい step を増やす場合はこの Javadoc も同時に更新すること。</p>
 *
 * <h2>kind ごとの遷移列</h2>
 * <p>{@link BillingOperationStatus} の {@code CREATED → CALLING_STRIPE → {APPLIED|FAILED|
 * RECONCILIATION_REQUIRED|CANCELLED}} という status 遷移に対し、{@code step} は
 * 「どの Stripe 呼出／どの局面か」を kind ごとに区別する。共通の骨格は次の3段:</p>
 * <ol>
 *   <li>{@link #RECEIVED} — status=CREATED 時点（全 kind 共通の起票直後）</li>
 *   <li>kind 固有の「Stripe 呼出中」step — status=CALLING_STRIPE 時点</li>
 *   <li>{@link #RECONCILE_PENDING} — status=RECONCILIATION_REQUIRED 時点（全 kind 共通）</li>
 *   <li>{@link #FINALIZED} / {@link #ABORTED} — status が APPLIED（→FINALIZED）または
 *       FAILED/CANCELLED（→ABORTED）に確定した時点（全 kind 共通）</li>
 * </ol>
 *
 * <table border="1">
 *   <caption>kind別のCALLING_STRIPE中stepの対応</caption>
 *   <tr><th>kind</th><th>CALLING_STRIPE 中の step</th></tr>
 *   <tr><td>{@code CANCEL}</td><td>{@link #STRIPE_CANCEL_SUBSCRIPTION}</td></tr>
 *   <tr><td>{@code RESUME}</td><td>{@link #STRIPE_RESUME_SUBSCRIPTION}</td></tr>
 *   <tr><td>{@code DOWNGRADE_TO_CANCEL}</td><td>{@link #STRIPE_SCHEDULE_DOWNGRADE}</td></tr>
 *   <tr><td>{@code PLAN_CHANGE}</td><td>{@link #STRIPE_APPLY_PLAN_CHANGE}
 *       （UPGRADE系）または {@link #STRIPE_SCHEDULE_DOWNGRADE}（DOWNGRADE系。
 *       {@code billing_contract_changes.kind=DOWNGRADE} と対応）</td></tr>
 *   <tr><td>{@code MIGRATION}</td><td>{@link #STRIPE_MIGRATION_SETUP}
 *       （setup intent/schedule 作成）→ {@link #STRIPE_MIGRATION_CUTOVER}
 *       （旧契約 cancel + 新契約切替。同一 CALLING_STRIPE 区間内での2段 step 遷移を許容する）</td></tr>
 *   <tr><td>{@code MEMBER_REPRICE}</td><td>{@link #STRIPE_REPRICE_SCHEDULE}</td></tr>
 *   <tr><td>{@code REFUND}</td><td>{@link #STRIPE_REFUND_ISSUE}</td></tr>
 * </table>
 *
 * <p>整合性は {@code BillingContractOperationEntityDdlAlignmentTest}
 * （V196 の SQL ファイルを実読して {@code step VARCHAR(32) NOT NULL} に CHECK が無いことを確認する）
 * で機械担保する。</p>
 */
public enum BillingOperationStep {
    /** 全 kind 共通: status=CREATED 時点（起票直後・Stripe未呼出）。 */
    RECEIVED,

    /** CANCEL: Stripe Subscription cancel（または cancel_at_period_end 更新）API 呼出中。 */
    STRIPE_CANCEL_SUBSCRIPTION,

    /** RESUME: Stripe Subscription の cancel_at_period_end 解除 API 呼出中。 */
    STRIPE_RESUME_SUBSCRIPTION,

    /**
     * DOWNGRADE_TO_CANCEL / PLAN_CHANGE(DOWNGRADE系): Stripe Subscription Schedule 作成 API 呼出中
     * （{@code billing_contract_changes.stripe_schedule_ref} を確定させる区間）。
     */
    STRIPE_SCHEDULE_DOWNGRADE,

    /** PLAN_CHANGE(UPGRADE系): Stripe Subscription item 即時更新 API 呼出中。 */
    STRIPE_APPLY_PLAN_CHANGE,

    /** MIGRATION 前段: Stripe SetupIntent 発行・Subscription Schedule 作成 API 呼出中。 */
    STRIPE_MIGRATION_SETUP,

    /** MIGRATION 後段: 旧 Subscription の cancel と新契約への切替（cutover）API 呼出中。 */
    STRIPE_MIGRATION_CUTOVER,

    /** MEMBER_REPRICE: 次暦月適用の Subscription Schedule 作成 API 呼出中。 */
    STRIPE_REPRICE_SCHEDULE,

    /** REFUND: Stripe Refund 発行 API 呼出中。 */
    STRIPE_REFUND_ISSUE,

    /** 全 kind 共通: status=RECONCILIATION_REQUIRED 時点（reconcile ジョブによる確定待ち）。 */
    RECONCILE_PENDING,

    /** 全 kind 共通: status=APPLIED に確定した時点。 */
    FINALIZED,

    /** 全 kind 共通: status が FAILED または CANCELLED に確定した時点。 */
    ABORTED
}
