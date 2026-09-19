package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Billing Center PR6a: 契約操作 Saga（AC-1〜AC-21）の入口サービス。
 *
 * <p>期待する振る舞いは {@code BillingContractOperationSagaIT} 他の試練Aが AC 番号つきで固定している。</p>
 *
 * <h2>殿の設計判断 D1 — トランザクション分割</h2>
 * <pre>
 * tx1: reserve()           contract FOR UPDATE + version CAS + operation INSERT + pointer INSERT
 *   -- commit --           （AC-2: 片方だけ残らない / AC-4: ここで Stripe を呼ばない）
 *      markCallingStripe() CREATED -&gt; CALLING_STRIPE
 *      Stripe 呼び出し      Idempotency-Key = stripeIdempotencyKeyOf(operationId)（AC-5）
 * tx2: applyAndFinalize()  反映（cancelled_at / valid_until 等）+ APPLIED + pointer DELETE
 *                          tx2 が落ちたら RECONCILIATION_REQUIRED（pointer 保持・AC-19）
 * </pre>
 *
 * <p>409 は全て {@link EntitlementErrorCode#CHANGE_CONFLICT}（{@code ENTITLEMENT_021}）を用いる
 * （AC-38。{@code GlobalExceptionHandler} で 409 に登録済み。新設しない）。</p>
 */
@Slf4j
@Service
public class BillingContractOperationSagaService {

    /** Stripe 冪等キーの接頭辞（AC-5）。 */
    public static final String STRIPE_IDEMPOTENCY_KEY_PREFIX = "billing-operation-";

    /** {@code request_hash}（CHAR(64) NOT NULL）が未指定のときに格納する既定値（SYSTEM 経路）。 */
    private static final String EMPTY_REQUEST_HASH = "0".repeat(64);

    /** 予約直後の暫定 {@code idempotency_key}（persist 直後に operationId へ差し替える）。 */
    private static final String PENDING_IDEMPOTENCY_KEY = "0".repeat(36);

    /** MySQL の重複キーエラー番号（{@code ER_DUP_ENTRY}）。 */
    private static final int ER_DUP_ENTRY = 1062;

    /** pointer 表の物理名（重複キーエラーが<b>この表</b>を名指ししているかの判定に使う）。 */
    private static final String POINTER_TABLE_NAME = "active_billing_contract_operation_pointers";

    private final BillingContractOperationRepository operationRepository;
    private final ActiveBillingContractOperationPointerRepository pointerRepository;
    private final EntityManager entityManager;

    /**
     * {@code billing_contract_operations.billing_customer_id}（NOT NULL ＋ FK）を埋めるための解決口。
     *
     * <p>F20.1 決済フロー由来の契約は {@code billing_customer_id} を持たないため、契約の値を
     * そのまま写すと NOT NULL 違反で予約が必ず落ちる（実在欠陥）。詳細は
     * {@link BillingCustomerLinkPort} の Javadoc を参照。</p>
     */
    private final BillingCustomerLinkPort billingCustomerLinkPort;

    /** tx1 / tx2 / 終端化に用いる（呼び出し元の tx があれば参加する）。 */
    private final TransactionTemplate transactionTemplate;

    /** tx2 が落ちたときの補償（検疫記録）を<b>別トランザクション</b>で行うための template（AC-19）。 */
    private final TransactionTemplate compensationTransactionTemplate;

    /**
     * tx1（{@link #reserve}）専用の template。伝播は既定（{@code REQUIRED}）のままだが、
     * 分離レベルだけ {@code READ COMMITTED} を指定する。
     *
     * <p>理由は {@link #reserve} 内のコメントを参照。この設定は<b>この template が開始した
     * トランザクションにのみ</b>効き、他ドメインにも本クラスの他メソッドにも波及しない
     * （{@code TransactionTemplate} はインスタンス単位の設定であり、分離レベルは
     * トランザクション終了時に接続へ復元される）。</p>
     */
    private final TransactionTemplate reserveTransactionTemplate;

    /**
     * 一括 UPDATE は {@code @PreUpdate} を経由しないため {@code updated_at} を明示的に渡す必要がある。
     * その時刻の出所（テストでも固定できる注入 Clock）。{@code updated_at} は「起きた瞬間」であり
     * {@link java.time.Instant} で扱う（日時方針 §1）。
     */
    private final java.time.Clock clock;

    public BillingContractOperationSagaService(
            BillingContractOperationRepository operationRepository,
            ActiveBillingContractOperationPointerRepository pointerRepository,
            EntityManager entityManager,
            BillingCustomerLinkPort billingCustomerLinkPort,
            PlatformTransactionManager transactionManager,
            java.time.Clock clock) {
        this.clock = clock;
        this.operationRepository = operationRepository;
        this.pointerRepository = pointerRepository;
        this.entityManager = entityManager;
        this.billingCustomerLinkPort = billingCustomerLinkPort;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.compensationTransactionTemplate = new TransactionTemplate(transactionManager);
        this.compensationTransactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reserveTransactionTemplate = new TransactionTemplate(transactionManager);
        this.reserveTransactionTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 予約（tx1）の入力。
     *
     * @param contractId              操作対象契約
     * @param kind                    操作種別
     * @param expectedContractVersion 利用者が提示した契約 version（CAS・AC-1）。
     *                                SYSTEM 経路で CAS を行わない場合のみ {@code null}
     * @param actorKind               USER / SYSTEM（AC-13）
     * @param actorUserId             {@code actorKind=USER} のとき必須・{@code SYSTEM} のとき {@code null}
     * @param requestHash             冪等キー使い回し検出用の request body ハッシュ（SHA-256 hex 64桁）
     */
    public record ReserveCommand(
            UUID contractId,
            BillingOperationKind kind,
            Long expectedContractVersion,
            BillingOperationActorKind actorKind,
            Long actorUserId,
            String requestHash) {
    }

    /**
     * 予約（tx1）の結果。
     *
     * @param operationId     採番された {@code billing_contract_operations.id}
     * @param contractId      操作対象契約
     * @param kind            操作種別
     * @param status          予約直後の状態（必ず {@link BillingOperationStatus#CREATED}）
     * @param step            予約直後の step（必ず {@link BillingOperationStep#RECEIVED}・AC-11）
     * @param contractVersion CAS 後の契約 version
     */
    public record OperationReservation(
            UUID operationId,
            UUID contractId,
            BillingOperationKind kind,
            BillingOperationStatus status,
            BillingOperationStep step,
            Long contractVersion) {
    }

    /**
     * tx1: 契約を {@code SELECT ... FOR UPDATE} で取り、version CAS を検証し、operation 行と
     * pointer 行を<b>同一トランザクションで</b> INSERT して commit する（AC-1/AC-2/AC-3/AC-4/AC-13/AC-14）。
     *
     * <p>Stripe はここでは<b>呼ばない</b>（AC-4）。{@code idempotency_key} 列には
     * operationId（UUID 36文字）を格納する（AC-32。任意長の HTTP ヘッダ値を入れない）。</p>
     *
     * @param command 予約要求
     * @return 予約結果
     * @throws com.mannschaft.app.common.BusinessException
     *         {@link EntitlementErrorCode#CHANGE_CONFLICT}（409）—
     *         pointer が既に存在する（AC-3/AC-14）／version CAS 不一致（AC-1）／
     *         契約が検疫中（AC-8）のとき
     * @throws IllegalArgumentException actor_kind と actorUserId の組合せが CHECK 制約に反するとき（AC-13）
     */
    public OperationReservation reserve(ReserveCommand command) {
        Objects.requireNonNull(command, "command は必須である");
        Objects.requireNonNull(command.contractId(), "contractId は必須である");
        Objects.requireNonNull(command.kind(), "kind は必須である");
        // chk_bco_actor（actor_kind と created_by の対）を DB へ到達させる前に弾く（AC-13）。
        requireValidActor(command.actorKind(), command.actorUserId());

        return reserveTransactionTemplate.execute(tx -> {
            BillingContractEntity contract = lockContractForUpdate(command.contractId());

            // 進行中 operation の lease がある（検疫を含む）契約は 409（AC-3 / AC-8 / AC-14）。
            //
            // 【必須・AC-122】この判定は「最新のコミット済み」を読まねばならない。
            // 直前の contract 行の SELECT ... FOR UPDATE は「契約単位で直列化する」ためのものだが、
            // MySQL 既定の REPEATABLE READ では、その後に続く素の SELECT は自分のトランザクション
            // 開始時点のスナップショットを読む。並行する2本の change 要求は、どちらも「pointer が無い」
            // スナップショットを掴んでから contract のロック待ちに入るため、後続側もこの判定を
            // 素通りし、409 ではなく pointer の主キー重複（DataIntegrityViolation → 500）で落ちていた。
            //
            // 【採らない形1・ロック読み】pointer を PESSIMISTIC_WRITE で読むと 409 では決着するが、
            // <b>存在しない行へのロック読みはギャップロックを取る</b>。この表は契約あたり高々1行で
            // 通常ほぼ空のため、そのギャップは実質「表全体」であり、無関係な別契約の予約どうしが
            // 同じギャップを掴み合い、INSERT の insert-intention lock と衝突して<b>デッドロック</b>に
            // なった（実測: {@code BillingCustomerLinkConcurrencyIT}）。
            //
            // 【採らない形2・REQUIRES_NEW の読み取り】新しいトランザクションで読めばギャップロックは
            // 消えるが、外側 tx が接続を保持したまま<b>2本目の接続</b>を要求する。異なる契約への予約が
            // 接続プール上限ぶん同時に入ると、全スレッドが互いの接続解放を待って総崩れになる
            // （CI 上限5・本番既定50。Codex 検分 P1）。デッドロックをより広い同時失敗に置き換えただけである。
            //
            // 【採る形】tx1 自体を READ COMMITTED で開く。READ COMMITTED は
            //   - 文ごとに新しい読み取りビューを取るため、<b>素の SELECT で最新のコミット済みが読める</b>
            //   - InnoDB がギャップロックを実質取らない（外部キー検査と重複キー検査を除く）
            //   - <b>追加の接続を要さない</b>
            // の3つを同時に満たす。同一契約の直列性は contract 行の X ロックが保証しており、
            // 後続側は先行側の commit（＝ロック解放）を待ってからこの読み取りに入るため、
            // 先行側の pointer を必ず見て CHANGE_CONFLICT（409）で決着する。
            if (pointerRepository.findById(command.contractId()).isPresent()) {
                throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
            }
            // version CAS（AC-1）。SYSTEM 経路で CAS を行わない場合のみ null を許す。
            if (command.expectedContractVersion() != null
                    && !command.expectedContractVersion().equals(contract.getVersion())) {
                throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
            }

            UUID billingCustomerId = resolveBillingCustomerId(contract);

            BillingContractOperationEntity operation = BillingContractOperationEntity.builder()
                    .contractId(contract.getId())
                    .billingCustomerId(billingCustomerId)
                    .organizationId(contract.getOrganizationId())
                    .kind(command.kind())
                    .status(BillingOperationStatus.CREATED)
                    .step(BillingOperationTransitions.stepFor(
                            command.kind(), BillingOperationStatus.CREATED))
                    .idempotencyKey(PENDING_IDEMPOTENCY_KEY)
                    .requestHash(command.requestHash() == null
                            ? EMPTY_REQUEST_HASH : command.requestHash())
                    .stripeSubscriptionRef(contract.getPspSubscriptionRef())
                    .version(0L)
                    .actorKind(command.actorKind())
                    .createdBy(command.actorUserId())
                    .build();
            entityManager.persist(operation);
            // AC-32: idempotency_key CHAR(36) に入れるのは operationId であり、HTTP ヘッダ値ではない。
            operation.setIdempotencyKey(operation.getId().toString());
            entityManager.flush();

            // AC-2: pointer INSERT は operation INSERT と同一トランザクション。片方だけ残らない。
            //
            // 【必須・AC-122 の最終防波堤】上の事前判定は「tx1 を自分で開いたとき」だけ
            // READ COMMITTED で行われる。{@code BillingContractService#cancelPaidAtPeriodEnd} のように
            // 呼び出し元が既にトランザクションを持つ経路では、伝播 REQUIRED で<b>参加する</b>ため
            // Spring は分離レベルの指定を黙って無視し（既定 validateExistingTransaction=false）、
            // 判定は呼び出し元の分離レベル（REPEATABLE READ）で行われる。そこで、主キー重複そのものを
            // 409 へ写像して「どの分離レベルでも 500 にならない」ことを構造で保証する。
            // ★これは握り潰しではない（剥がさないこと）—— InnoDB は並行 INSERT に対する重複キー
            // エラーを<b>相手の commit 後</b>に返す。つまりこの例外は「先行する lease が確実に
            // 存在する」という<b>事実そのもの</b>であり、409 はその事実を正しく写しているだけで、
            // 何かを隠してはいない。変換するのは pointer 表を名指しした主キー重複だけであり、
            // 他表・他制約の整合性違反はそのまま素通しする（本物の不整合を 409 で隠さない）。
            try {
                entityManager.persist(ActiveBillingContractOperationPointerEntity.builder()
                        .contractId(contract.getId())
                        .operationId(operation.getId())
                        .build());
                entityManager.flush();
            } catch (RuntimeException e) {
                // 掴む型を広く取り、<b>写像するかどうかは原因鎖の実体で決める</b>。
                // flush() の重複キーは Hibernate の ConstraintViolationException（HibernateException）
                // として素で飛ぶことも、JPA/Spring の型へ包まれて飛ぶこともあり、型で絞ると
                // 経路によって取りこぼして 500 に戻る。重複キーでなければそのまま素通しする。
                if (!isPointerPrimaryKeyDuplicate(e)) {
                    throw e;
                }
                log.info("AC-122: pointer の主キー重複で並行予約に敗れたため 409 で決着させる: contractId={}",
                        command.contractId());
                throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
            }

            return new OperationReservation(
                    operation.getId(), contract.getId(), operation.getKind(),
                    operation.getStatus(), operation.getStep(), contract.getVersion());
        });
    }

    /**
     * Stripe 呼び出し直前に {@code CREATED -> CALLING_STRIPE} へ遷移させる（AC-10）。
     * step も kind に応じた値へ進める（AC-11）。
     *
     * @param operationId 対象 operation
     * @throws IllegalStateException 許可されない遷移のとき
     */
    public void markCallingStripe(UUID operationId) {
        transactionTemplate.executeWithoutResult(tx ->
                transitionInCurrentTransaction(
                        operationId, BillingOperationStatus.CALLING_STRIPE, null, false));
    }

    /**
     * tx2: 渡された反映処理と、operation の APPLIED 化・pointer DELETE を
     * <b>同一トランザクション</b>で行う（AC-7/AC-18）。
     *
     * <p>反映処理が例外を投げた場合は tx2 全体をロールバックし、<b>別トランザクション</b>で
     * operation を {@link BillingOperationStatus#RECONCILIATION_REQUIRED} へ倒して
     * pointer を保持したまま例外を呼出元へ伝播する（AC-19。黙って成功にしない）。</p>
     *
     * <p><b>先着競合の収束（Codex 検分 P1-2）</b>: Stripe の同期呼び出し成功後に
     * {@code customer.subscription.updated} が届くと、停止窓の回収
     * （{@link BillingContractOperationRecoveryService#recoverBySubscriptionRef}）が本メソッドより
     * 先に同じ operation を {@code APPLIED} へ確定させることがある。このとき状態機械へ
     * {@code APPLIED -> APPLIED} を投げると自己遷移として拒否され（AC-10。自己遷移の禁止は
     * 正しいので緩めない）、<b>解約は成立しているのに利用者には失敗が返る</b>。
     * そこで「既に同じ結末へ確定している」ときだけ成功へ収束させる。
     * 別の結末（{@code FAILED}/{@code CANCELLED}）や検疫中は従来どおり例外にする——
     * 結末が違うものを黙って成功にしてはならない。</p>
     *
     * <p><b>反映より前に結末を確かめる（Codex 再検分 P1）</b>: 判定を {@code reflection} の
     * 後ろに置くと、収束する場合でも<b>反映だけは実行して commit してしまう</b>。その隙に
     * 後続の利用者操作（回収済み {@code CANCEL} の後の {@code RESUME} 等）が完了していると、
     * 遅れて届いた古い反映が<b>後から再適用されて新しい操作を上書きする</b>——
     * 利用者から見れば「撤回したのに解約されたまま」になる。
     * 以前は自己遷移エラーがこの再適用を偶然巻き戻していたため表面化しなかったが、
     * 収束させた時点でその保険は外れる。したがって
     * <b>operation 行を {@code SELECT ... FOR UPDATE} で掴んで結末を確かめてから</b>
     * 反映を実行する（正本 D1 の「単一の耐久 lease と tx2 の原子性」）。
     * ロックは tx2 の間ずっと保持されるため、回収側の CAS は待たされ、
     * どちらが先着しても二重適用は起きない。</p>
     *
     * @param operationId 対象 operation
     * @param reflection  DB 反映処理（cancelled_at / valid_until の更新等）
     * @param <T>         反映処理の戻り値型
     * @return 反映処理の戻り値
     * @throws IllegalStateException 既に別経路が同じ結末へ確定させており、かつ収束時に返す
     *                               読み取りが与えられていないとき（2引数版）。
     *                               収束させたい呼び出し元は
     *                               {@link #applyAndFinalize(UUID, Supplier, Supplier)} を使う
     */
    public <T> T applyAndFinalize(UUID operationId, Supplier<T> reflection) {
        return applyAndFinalize(operationId, reflection, null);
    }

    /**
     * tx2（収束時の読み取り付き）。
     *
     * <p>停止窓の回収が先着して既に {@code APPLIED} を確定させていた場合、<b>反映は実行せず</b>
     * {@code convergedRead} が返す「現在の DB の姿」を呼出元へ返す。反映を再実行しないのが要である
     * ——再実行すると、その後に成立した新しい利用者操作を古い反映が上書きする。</p>
     *
     * <p>{@code convergedRead} には<b>読み取りだけ</b>を渡すこと（{@code cancelled_at} 等を
     * 書き換えてはならない）。収束時に返すべきは「自分が書いたはずの姿」ではなく
     * <b>いま DB にある真実</b>である。</p>
     *
     * @param operationId   対象 operation
     * @param reflection    DB 反映処理（自分が先着したときだけ実行される）
     * @param convergedRead 既に同じ結末へ確定済みのときに返す読み取り（{@code null} なら例外）
     * @param <T>           戻り値型
     * @return 反映の戻り値、または収束時は {@code convergedRead} の戻り値
     */
    public <T> T applyAndFinalize(
            UUID operationId, Supplier<T> reflection, Supplier<T> convergedRead) {
        Objects.requireNonNull(reflection, "reflection は必須である");
        try {
            return transactionTemplate.execute(tx -> {
                // ★順序が要: 反映より前に行を掴んで結末を確かめる（P1 退行の根治）。
                BillingContractOperationEntity locked = entityManager.find(
                        BillingContractOperationEntity.class, operationId,
                        LockModeType.PESSIMISTIC_WRITE);
                if (locked != null && locked.getDeletedAt() == null
                        && locked.getStatus() == BillingOperationStatus.APPLIED) {
                    // 回収が先着して同じ結末を確定済み。反映は実行しない（再適用の禁止）。
                    // pointer の解放だけ冪等に念押しする（物理 DELETE は 0 件でも害が無い）。
                    pointerRepository.hardDeleteByContractIdAndOperationId(
                            locked.getContractId(), operationId);
                    entityManager.flush();
                    if (convergedRead == null) {
                        throw new IllegalStateException(
                                "既に APPLIED へ確定済みだが、収束時に返す読み取りが無い: "
                                        + operationId);
                    }
                    return convergedRead.get();
                }
                T applied = reflection.get();
                // 反映と同一トランザクションで terminal 化＋pointer 解放（AC-7 / AC-18）。
                // 結末が違う（FAILED/CANCELLED/検疫）場合はここで従来どおり例外になる。
                transitionInCurrentTransaction(
                        operationId, BillingOperationStatus.APPLIED, null, true);
                return applied;
            });
        } catch (RuntimeException e) {
            // AC-19: tx2 はロールバック済み。別トランザクションで検疫へ倒し、例外は握らず再送する。
            quarantineAfterFailedApply(operationId, e);
            throw e;
        }
    }

    /**
     * Stripe 失敗時: operation を {@link BillingOperationStatus#FAILED} へ CAS し、
     * 同一トランザクションで pointer を DELETE する（AC-6/AC-7）。
     *
     * @param operationId 対象 operation
     * @param errorCode   {@code error_code} 列へ記録するコード
     */
    public void failAndRelease(UUID operationId, String errorCode) {
        transactionTemplate.executeWithoutResult(tx ->
                transitionInCurrentTransaction(
                        operationId, BillingOperationStatus.FAILED, errorCode, true));
    }

    /**
     * 停止窓(a) の回収: Stripe 呼び出し前の operation を
     * {@link BillingOperationStatus#CANCELLED} へ倒し、同一トランザクションで pointer を DELETE する
     * （AC-7/AC-10 の {@code CREATED -> CANCELLED} 辺・D8）。
     *
     * @param operationId 対象 operation
     * @param errorCode   {@code error_code} 列へ記録するコード（不要なら {@code null}）
     */
    public void cancelAndRelease(UUID operationId, String errorCode) {
        transactionTemplate.executeWithoutResult(tx ->
                transitionInCurrentTransaction(
                        operationId, BillingOperationStatus.CANCELLED, errorCode, true));
    }

    /**
     * 検疫へ倒す（AC-8）。{@code RECONCILIATION_REQUIRED} は terminal ではないため
     * <b>pointer は保持する</b>。以後その契約への利用者起点の mutation は全て 409 になる。
     *
     * @param operationId 対象 operation
     * @param errorCode   {@code error_code} 列へ記録するコード
     */
    public void quarantine(UUID operationId, String errorCode) {
        transactionTemplate.executeWithoutResult(tx -> transitionInCurrentTransaction(
                operationId, BillingOperationStatus.RECONCILIATION_REQUIRED, errorCode, false));
    }

    /**
     * reconcile: 検疫中の operation を terminal へ確定させる（AC-9/AC-10）。
     * terminal が確定したときだけ pointer が解放される。
     *
     * @param operationId    対象 operation
     * @param terminalStatus 確定先（APPLIED / FAILED / CANCELLED のいずれか）
     * @param errorCode      {@code error_code} 列へ記録するコード（不要なら {@code null}）
     * @throws IllegalStateException {@code terminalStatus} が terminal でないとき
     */
    public void reconcile(
            UUID operationId, BillingOperationStatus terminalStatus, String errorCode) {
        if (!BillingOperationTransitions.isTerminal(terminalStatus)) {
            throw new IllegalStateException(
                    "reconcile の確定先は terminal でなければならない: " + terminalStatus);
        }
        transactionTemplate.executeWithoutResult(tx ->
                transitionInCurrentTransaction(operationId, terminalStatus, errorCode, true));
    }

    /**
     * 利用者起点の mutation の入口ガード（AC-3/AC-8/AC-20/AC-21）。
     * pointer が存在する契約では 409 を投げる。
     *
     * @param contractId 対象契約
     * @throws com.mannschaft.app.common.BusinessException
     *         {@link EntitlementErrorCode#CHANGE_CONFLICT}（409）
     */
    public void requireNoActiveOperation(UUID contractId) {
        if (contractId != null && pointerRepository.existsById(contractId)) {
            throw new BusinessException(EntitlementErrorCode.CHANGE_CONFLICT);
        }
    }

    /**
     * D3 — SYSTEM 経路の検疫貫通（AC-16/AC-17/AC-17b）。
     *
     * <p>退会 purge と {@code customer.subscription.deleted} は検疫中でも通す。その際、残っている
     * 非終端 operation を<b>同一トランザクションで</b> {@link BillingOperationStatus#CANCELLED} へ
     * 終端化してから pointer を削除する（孤児を残さない）。再入・並行実行で二重に効いてはならない
     * （AC-17b）。pointer が無ければ何もしない（冪等）。</p>
     *
     * @param contractId 対象契約
     * @return 終端化した operation 件数（0 または 1）
     */
    public int terminateNonTerminalAndRelease(UUID contractId) {
        if (contractId == null) {
            return 0;
        }
        return terminateNonTerminalAndReleaseAll(java.util.List.of(contractId));
    }

    /**
     * D3 の検疫貫通を<b>複数契約へ一括で</b>適用する（AC-16/AC-17/AC-17b ＋ AC-72b）。
     *
     * <p>意味は {@link #terminateNonTerminalAndRelease} と同一であり、違いは
     * <b>発行する SQL 本数が契約数 M に比例しない</b>ことだけである。退会 purge は契約数ぶん
     * ループするため、契約ごとに「契約ロック → pointer 取得 → operation 取得 → 終端化 → pointer 削除」を
     * 出すと 4M 本になる。ここでは次の本数に畳む。</p>
     *
     * <ul>
     *   <li>契約行の FOR UPDATE ロック: 1本（{@code IN} 句）</li>
     *   <li>pointer の取得: 1本</li>
     *   <li>operation の取得: 1本（pointer が1件も無ければ 0本）</li>
     *   <li>終端化の UPDATE: {@code (from status, kind)} の組み合わせ数ぶん。enum の直積が上限であり
     *       M には比例しない（実運用ではほぼ1本）</li>
     *   <li>pointer の DELETE: 1本</li>
     * </ul>
     *
     * <p>再入・並行実行での二重適用は、契約行のロック＋「status 一致を条件に持つ UPDATE」＋
     * 「読んだ pointer の contract_id だけを消す DELETE」で塞ぐ（AC-17b）。</p>
     *
     * @param contractIds 対象契約（null 要素・重複は無視する）
     * @return 終端化した operation 件数
     */
    public int terminateNonTerminalAndReleaseAll(java.util.Collection<UUID> contractIds) {
        if (contractIds == null || contractIds.isEmpty()) {
            return 0;
        }
        java.util.List<UUID> ids = contractIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return 0;
        }
        Integer terminated = transactionTemplate.execute(tx -> {
            // 契約行の排他ロックで並行到達（purge と webhook）を直列化する（AC-17b）。
            // 契約が消えていても pointer の掃除は行うため、1件も引けなくても続行する。
            entityManager.createQuery(
                            "SELECT c FROM BillingContractEntity c WHERE c.id IN :ids",
                            BillingContractEntity.class)
                    .setParameter("ids", ids)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultList();

            java.util.List<ActiveBillingContractOperationPointerEntity> pointers =
                    pointerRepository.findAllById(ids);
            if (pointers.isEmpty()) {
                // 既に別経路が解放済み。再入・並行実行で二重に効かせない（AC-17b・冪等）。
                return 0;
            }
            java.util.List<UUID> operationIds = pointers.stream()
                    .map(ActiveBillingContractOperationPointerEntity::getOperationId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();

            int count = 0;
            if (!operationIds.isEmpty()) {
                // 孤児を残さないため、pointer 削除と同一トランザクションで CANCELLED へ終端化する（D3）。
                // (現 status, kind) 単位にまとめる —— step は kind ごとに決まるため混ぜられない。
                java.util.Map<java.util.List<Object>, java.util.List<UUID>> grouped =
                        new java.util.LinkedHashMap<>();
                for (BillingContractOperationEntity operation
                        : operationRepository.findByIdInAndDeletedAtIsNull(operationIds)) {
                    if (BillingOperationTransitions.isTerminal(operation.getStatus())) {
                        continue;
                    }
                    BillingOperationTransitions.requireAllowed(
                            operation.getStatus(), BillingOperationStatus.CANCELLED);
                    grouped.computeIfAbsent(
                            java.util.List.of(operation.getStatus(), operation.getKind()),
                            k -> new java.util.ArrayList<>()).add(operation.getId());
                }
                for (java.util.Map.Entry<java.util.List<Object>, java.util.List<UUID>> e
                        : grouped.entrySet()) {
                    BillingOperationStatus from = (BillingOperationStatus) e.getKey().get(0);
                    BillingOperationKind kind = (BillingOperationKind) e.getKey().get(1);
                    count += operationRepository.compareAndSetStatusBulk(
                            e.getValue(), from, BillingOperationStatus.CANCELLED,
                            BillingOperationTransitions.stepFor(kind, BillingOperationStatus.CANCELLED),
                            SYSTEM_BYPASS_ERROR_CODE, java.time.Instant.now(clock));
                }
            }
            pointerRepository.hardDeleteByContractIdIn(
                    pointers.stream()
                            .map(ActiveBillingContractOperationPointerEntity::getContractId)
                            .toList());
            entityManager.flush();
            return count;
        });
        return terminated == null ? 0 : terminated;
    }


    // ================================================================
    // 内部実装
    // ================================================================

    /** D3 の検疫貫通で非終端 operation を終端化した理由（{@code error_code} 列へ記録する）。 */
    static final String SYSTEM_BYPASS_ERROR_CODE = "SYSTEM_BYPASS";

    /**
     * 現在のトランザクション内で operation の状態を進め、必要なら pointer を解放する。
     *
     * @param operationId    対象 operation
     * @param to             遷移先
     * @param errorCode      {@code error_code} へ記録する値（{@code null} なら据え置き）
     * @param releasePointer terminal 確定に伴い pointer を削除するか
     */
    private void transitionInCurrentTransaction(
            UUID operationId, BillingOperationStatus to, String errorCode, boolean releasePointer) {

        BillingContractOperationEntity operation = operationRepository
                .findByIdAndDeletedAtIsNull(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "operation が見つからない: " + operationId));
        BillingOperationTransitions.requireAllowed(operation.getStatus(), to);

        operation.setStatus(to);
        operation.setStep(BillingOperationTransitions.stepFor(operation.getKind(), to));
        if (errorCode != null) {
            operation.setErrorCode(errorCode);
        }
        operation.setVersion(operation.getVersion() == null ? 1L : operation.getVersion() + 1L);
        operationRepository.saveAndFlush(operation);

        if (releasePointer) {
            // AC-7: terminal 確定と同一トランザクションで lease を解放する。
            pointerRepository.hardDeleteByContractIdAndOperationId(
                    operation.getContractId(), operationId);
            entityManager.flush();
        }
    }

    /** tx2 失敗後の検疫記録（AC-19）。補償自体の失敗で元の例外を覆い隠さない。 */
    private void quarantineAfterFailedApply(UUID operationId, RuntimeException cause) {
        try {
            compensationTransactionTemplate.executeWithoutResult(tx ->
                    transitionInCurrentTransaction(
                            operationId, BillingOperationStatus.RECONCILIATION_REQUIRED,
                            "TX2_FAILED", false));
        } catch (RuntimeException compensationFailure) {
            log.error("PR6a: tx2 失敗後の検疫記録に失敗した（operationId={}）。"
                    + "停止窓(c) の回収対象として扱う必要がある", operationId, compensationFailure);
            cause.addSuppressed(compensationFailure);
        }
    }

    /**
     * operation に刻む {@code billing_customer_id} を決める。
     *
     * <h2>なぜ契約の値をそのまま使えないか（実在欠陥）</h2>
     * <p>{@code billing_contract_operations.billing_customer_id} は V196 で <b>NOT NULL ＋
     * {@code billing_customers} への FK</b> である。一方 F20.1 の決済フロー
     * （{@code BillingContractService#startPaidContract} → {@code activatePaidContract}）は
     * {@code psp_customer_ref} は焼き付けるが {@code billing_customer_id} を<b>一度も設定しない</b>。
     * したがって本番には「有償・ACTIVE・PSP 紐付あり・{@code billing_customer_id} が NULL」の契約が
     * 実在し、その契約への解約・撤回・プラン変更は予約の INSERT で必ず NOT NULL 違反になる。</p>
     *
     * <p>そこで欠けている紐付けをここで解決し、<b>契約行へ書き戻す</b>。契約は直前に
     * {@code FOR UPDATE} で押さえてあるので、同一トランザクション内の書き戻しは競合しない。
     * 一度書き戻せば以後の操作は素通りする（引き上げは契約あたり一度きり）。</p>
     *
     * @param contract ロック済みの契約
     * @return 非 NULL の {@code billing_customers.id}
     */
    private UUID resolveBillingCustomerId(BillingContractEntity contract) {
        UUID linked = contract.getBillingCustomerId();
        if (linked != null) {
            return linked;
        }
        UUID resolved = billingCustomerLinkPort.resolveOrProvision(
                contract.getScopeKind(), contract.getScopeId(),
                contract.getOrganizationId(), contract.getPspCustomerRef());
        // 欠けていた紐付けを修復する（症状を隠さず、原因である NULL そのものを解消する）。
        contract.setBillingCustomerId(resolved);
        entityManager.flush();
        return resolved;
    }

    /**
     * 例外の原因鎖に <b>pointer 表の主キー重複</b>（MySQL {@code ER_DUP_ENTRY} = 1062）が居るかを判定する。
     *
     * <p>{@code try} で囲っているのは pointer の INSERT 1文だけであり、その表で重複しうるのは
     * 主キー {@code contract_id}（＝lease の二重取得）だけである（{@code uk_abcop_operation} は
     * 採番したての operationId なので原理的に重複しない）。さらに<b>例外が pointer 表を名指しして
     * いることまで確かめる</b>ので、他表・他制約の一意違反を 409 で隠すことはない
     * （本物の不整合は握らず呼び出し元へ上げる）。</p>
     *
     * @param throwable 判定対象
     * @return 重複キーなら {@code true}
     */
    private static boolean isPointerPrimaryKeyDuplicate(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sqlException
                    && sqlException.getErrorCode() == ER_DUP_ENTRY
                    && mentionsPointerTable(sqlException.getMessage())) {
                return true;
            }
            if (t instanceof org.hibernate.exception.ConstraintViolationException hibernateViolation
                    && mentionsPointerTable(hibernateViolation.getConstraintName())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * MySQL 8.0 は重複キーの相手を {@code '<表名>.<キー名>'} の形で報告する
     * （例: {@code Duplicate entry '…' for key 'active_billing_contract_operation_pointers.PRIMARY'}）。
     * <b>この表名が現れることを写像の条件にする</b>ことで、他表・他制約の 1062 を巻き込まない。
     *
     * @param text 例外メッセージまたは制約名（{@code null} 可）
     * @return pointer 表を名指ししていれば {@code true}
     */
    private static boolean mentionsPointerTable(String text) {
        return text != null && text.contains(POINTER_TABLE_NAME);
    }

    /** 契約行を {@code SELECT ... FOR UPDATE} で取得する（AC-1）。 */
    private BillingContractEntity lockContractForUpdate(UUID contractId) {
        BillingContractEntity contract = entityManager.find(
                BillingContractEntity.class, contractId, LockModeType.PESSIMISTIC_WRITE);
        if (contract == null || contract.getDeletedAt() != null) {
            throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);
        }
        return contract;
    }

    /** {@code chk_bco_actor}（USER は created_by 必須・SYSTEM は NULL 必須）を実装側でも強制する（AC-13）。 */
    private void requireValidActor(BillingOperationActorKind actorKind, Long actorUserId) {
        if (actorKind == null) {
            throw new IllegalArgumentException("actorKind は必須である");
        }
        if (actorKind == BillingOperationActorKind.USER && actorUserId == null) {
            throw new IllegalArgumentException(
                    "actor_kind=USER の operation は created_by が必須である");
        }
        if (actorKind == BillingOperationActorKind.SYSTEM && actorUserId != null) {
            throw new IllegalArgumentException(
                    "actor_kind=SYSTEM の operation は created_by が NULL でなければならない");
        }
    }

    /**
     * Stripe 呼び出しの Idempotency-Key（AC-5）。
     *
     * @param operationId 対象 operation
     * @return {@code billing-operation-{operationId}}
     */
    public static String stripeIdempotencyKeyOf(UUID operationId) {
        return STRIPE_IDEMPOTENCY_KEY_PREFIX + operationId;
    }
}
