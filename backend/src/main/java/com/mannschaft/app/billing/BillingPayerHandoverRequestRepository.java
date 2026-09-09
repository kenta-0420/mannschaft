package com.mannschaft.app.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 柱③-B 組織契約の請求担当引継（CMP-260901-1538・V203）:
 * {@code billing_payer_handover_requests} リポジトリ。
 *
 * <p>このフェーズ（PR-1）では DDL＋読み取り専用の土台のみ。引継要求作成・承諾・切替TX等の
 * Service は後続 PR（設計書 PR-2）のスコープ。</p>
 */
public interface BillingPayerHandoverRequestRepository
        extends JpaRepository<BillingPayerHandoverRequestEntity, UUID> {

    /**
     * 対象契約に対する進行中（非終端）の引継要求を取得する。
     * {@code open_old_contract_id} 生成列と同じ「終端状態以外」の判定をアプリ層でも表現する
     * （生成列自体は DB 側の UNIQUE 制約担保用であり、このメソッドは Java 側からの参照用）。
     */
    List<BillingPayerHandoverRequestEntity> findByOldContractIdAndStatusNotIn(
            UUID oldContractId, List<PayerHandoverStatus> terminalStatuses);

    Optional<BillingPayerHandoverRequestEntity> findByNewContractId(UUID newContractId);

    /**
     * 旧 payer 起点で、指定状態の要求を引く（柱③-B PR-3・退会取消時の終端化に使う）。
     *
     * <p>設計書 §4.2 の遷移表は退会取消時に {@code REQUESTED → FAILED} と定めている。終端化しないと
     * {@code REQUESTED} 行が残り、生成列 + {@code uk_bphr_open_old_contract} が同一契約への
     * 次の引継要求をブロックし続ける（Codex 検分1巡目 P1-3）。</p>
     */
    List<BillingPayerHandoverRequestEntity> findByOldPayerUserIdAndStatus(
            Long oldPayerUserId, PayerHandoverStatus status);

    /**
     * 引継要求を <b>{@code SELECT ... FOR UPDATE}</b> で行ロックして取得する（設計書 §4.2・§5.6・AC-12）。
     *
     * <p>複数 ADMIN が同時に承諾操作を行っても、状態遷移（{@code REQUESTED → ACCEPTED}）が
     * 1 回だけ有効になるようにするための直列化点である。{@code uk_bphr_open_old_contract}
     * （生成列 + UNIQUE）は「進行中の要求が同時に1件」を保証するが、<b>同一行に対する
     * competing update は防がない</b>ため、承諾処理は必ず本メソッドで行をロックしてから
     * 状態を判定・遷移させること（設計書 §4.2 の注記）。</p>
     *
     * <p>呼び出しは書き込みトランザクションの内側からのみ行う（ロックは commit まで保持される）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM BillingPayerHandoverRequestEntity h WHERE h.id = :id")
    Optional<BillingPayerHandoverRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 切替TXの対象（{@code SWITCHING} かつ旧契約の期末に到達済み）の引継要求 ID を返す（設計書 §3.6 (b)）。
     *
     * <p>pointer 切替の発火条件は「旧契約の {@code current_period_end} に到達したか」という
     * <b>アプリ側で判定可能な時刻条件のみ</b>であり、{@code invoice.paid} の到達は待たない
     * （R2-P1-2 裁定・AC-27）。</p>
     *
     * <p>{@code billing_contracts.current_period_end} は {@link LocalDateTime}、handover 側は
     * {@code Instant} であるため、呼び出し側が {@code now} を DB 格納値と同じ壁時計へ変換して渡す。</p>
     *
     * <p><b>PR-4</b>: 抽出対象は {@link PayerHandoverStatus#SWITCHING} だけではない。
     * {@link PayerHandoverStatus#PARTIALLY_COMPLETED}（＝Stripe 側は確定済みでローカル切替TXのみ未了・
     * 非終端のリトライ対象・§3.5）も同じバッチが拾い直さなければ、pointer が旧のまま宙ぶらりんで残る。
     * {@code MANUAL_INTERVENTION} は<b>含めない</b>——運用者の {@code RESUME} 待ちであり、
     * 機械的に切替を再試行してはならない状態だからである（§3.6.2）。</p>
     *
     * @param statuses 通常 {@code SWITCHING} と {@code PARTIALLY_COMPLETED}
     * @param now      現在時刻（{@code billing_contracts} の壁時計へ変換済み）
     */
    @Query("SELECT h.id FROM BillingPayerHandoverRequestEntity h "
            + "JOIN BillingContractEntity c ON c.id = h.oldContractId "
            + "WHERE h.status IN :statuses "
            + "AND c.currentPeriodEnd IS NOT NULL AND c.currentPeriodEnd <= :now "
            + "ORDER BY h.requestedAt")
    List<UUID> findSwitchDueIds(@Param("statuses") List<PayerHandoverStatus> statuses,
                                @Param("now") LocalDateTime now);

    /**
     * 旧サブスクへの {@code cancel_at_period_end=true} 設定が<b>確認できていない</b>引継要求 ID を返す
     * （設計書 §3.6.1(a)・AC-34・PR-4 の夜次照合バッチの抽出）。
     *
     * <p><b>なぜ要るか</b>: 設定 API の呼び出しと {@code old_cancel_scheduled_at} の永続化は
     * 別操作であり原子性が無い（Stripe は外部システムで DB tx に巻き込めない）。
     * 「Stripe では成功したが DB 書き込み前にクラッシュ」「設定 API 自体が失敗」のいずれでも、
     * この列は NULL のまま残る。放置すると<b>承諾は進んでいるのに旧サブスクが通常課金を続ける</b>。</p>
     *
     * <p><b>抽出を {@code SWITCHING}/{@code PARTIALLY_COMPLETED} に限る理由</b>: 旧サブスクへの予約は
     * 引継確定（{@code checkout.session.completed}）と同時に行う設計であり、{@code ACCEPTED} 段階で
     * 未設定なのは<b>正常</b>である（まだ確定していない）。ここを含めると、正常な進行中の行に対して
     * 毎晩 Stripe を叩き、予約すべきでない旧サブスクを予約してしまう。
     * {@code MANUAL_INTERVENTION} も除く——人手対応中に自動で Stripe 状態を動かさない（§3.6.2）。</p>
     *
     * @param statuses 通常 {@code SWITCHING} と {@code PARTIALLY_COMPLETED}
     */
    @Query("SELECT h.id FROM BillingPayerHandoverRequestEntity h "
            + "WHERE h.status IN :statuses "
            + "AND h.oldCancelScheduledAt IS NULL "
            + "ORDER BY h.requestedAt")
    List<UUID> findOldCancelScheduleUnconfirmedIds(
            @Param("statuses") List<PayerHandoverStatus> statuses);

    /**
     * 猶予期限（既定14日）を過ぎたまま<b>誰にも承諾されていない</b>引継要求 ID を返す
     * （設計書 §5.3・§5.5 ⑤・AC-21・PR-4）。
     *
     * <p><b>なぜ要るか</b>: 期限切れの判定は従来<b>承諾操作が来たときにしか行われなかった</b>
     * （{@code validateAcceptable} の中で {@code EXPIRED} へ倒す）。しかし AC-21 が定めるのは
     * まさに「複数 ADMIN が全員承諾を拒否／無視した」ケースであり、その場合<b>承諾操作は永久に来ない</b>。
     * すると {@code REQUESTED} 行は非終端のまま残り続け、§5.4 により purge の期末解約フォールバックが
     * スキップされ続け、生成列 + UNIQUE が同一契約への再要求もブロックし続ける——
     * <b>旧 payer への課金が止まらないまま固まる</b>。期限到来を能動的に検出する経路が要る。</p>
     *
     * <p>{@code psp_new_subscription_ref IS NULL} を条件に含めるのは、参照が確定している行を
     * ここで終端化すると Stripe 上のサブスクを孤児として残すためである（そちらは
     * {@link #findExpiredUnresolvedAcceptedIds} 経路が Stripe 照合を伴って決着させる）。</p>
     *
     * @param statuses 通常 {@code REQUESTED} と {@code REQUIRES_PAYMENT_METHOD}（§4.2 遷移表の
     *                 「{@code EXPIRED} への遷移元」2状態）
     * @param now      現在時刻（{@code expires_at} と同じ {@code Instant}）
     */
    @Query("SELECT h.id FROM BillingPayerHandoverRequestEntity h "
            + "WHERE h.status IN :statuses "
            + "AND h.pspNewSubscriptionRef IS NULL "
            + "AND h.expiresAt <= :now "
            + "ORDER BY h.requestedAt")
    List<UUID> findOverdueUnacceptedIds(@Param("statuses") List<PayerHandoverStatus> statuses,
                                        @Param("now") Instant now);

    /**
     * {@code SWITCHING} のまま滞留している引継要求 ID を返す
     * （設計書 §5.5 ④・PR-4 射程の「{@code SWITCHING} 詰まり監視」・AC-20）。
     *
     * <p>承諾確定（{@code accepted_at}）から一定時間を過ぎてなお {@code SWITCHING} に留まる行は、
     * 追加認証（SCA/3DS）が完了していないか、引継が事実上停止している。旧期末到達まで放置すると
     * <b>旧サブスクが先に終了してしまい、差し戻しても継続を復旧できない</b>。
     * 抽出には Stripe 照会を伴わない（実物の再検証は呼び出し側が行う）。</p>
     *
     * <h2>抽出条件が2本ある理由（PR-4 Codex検分2巡目 P1-6）</h2>
     * <p>「承諾+24時間」だけだと、<b>旧期末まで24時間未満の契約</b>では監視より先に旧サブスクが
     * 期末終了してしまう。そこで {@code periodEndCutoff}（旧期末が目前）でも拾い、
     * 差し戻しが有効なうちに決着させる。</p>
     *
     * <h2>キーセット送りで返す理由（同 P1-5）</h2>
     * <p>この抽出は<b>処理しても状態が変わらない行</b>（認証完了済みで旧期末を待っているだけの
     * 正常な {@code SWITCHING}）を返しうる。他の照合クエリは処理すると必ず状態が動いて集合から
     * 抜けるが、この抽出だけは抜けない。固定の「先頭N件」で切ると、その種の古い行がN件あるだけで
     * <b>後続の認証未解決行が永久に検査されない</b>（＝24時間期限を満たせない）。よって
     * {@code (id, acceptedAt)} を返し、呼び出し側が {@code afterAcceptedAt} を carry して前へ進む。</p>
     *
     * @param cutoff          {@code accepted_at} がこの時刻以前の行を滞留とみなす
     * @param periodEndCutoff 旧契約の期末がこの時刻以前なら、承諾からの経過に関わらず拾う
     * <h2>複合カーソルである理由（3巡目 P1-3）</h2>
     * <p>{@code accepted_at} 単独のカーソル（{@code > :afterAcceptedAt}）では、
     * <b>同一 {@code accepted_at} の行がページサイズを超えると、1ページ目に入らなかった同時刻行が
     * 次ページの条件から全て脱落する</b>。承諾はバッチ処理や同時操作で同一秒に固まりうるため、
     * {@code (accepted_at, id)} の複合カーソルで厳密に前へ進む。</p>
     *
     * <h2>正常待機行を除外する理由（同）</h2>
     * <p>{@code setup_intent_verified_at IS NULL} を条件に含める。認証完了を確認済みの行は
     * 処理しても状態が変わらず、抽出に残り続けると実行件数の上限を埋めて
     * <b>後続の認証未解決行を永久に飢餓させる</b>（カーソルは実行のたびに初期化されるため、
     * 上限に達する限りその先へは決して到達しない）。</p>
     *
     * @param afterAcceptedAt 直前ページの最後の {@code accepted_at}（初回は {@code Instant.EPOCH}）
     * @param afterId         直前ページの最後の {@code id}（同一 {@code accepted_at} 内の順序）
     */
    @Query("SELECT h.id, h.acceptedAt FROM BillingPayerHandoverRequestEntity h "
            + "JOIN BillingContractEntity c ON c.id = h.oldContractId "
            + "WHERE h.status = :status "
            + "AND h.pspNewSubscriptionRef IS NOT NULL "
            + "AND h.acceptedAt IS NOT NULL "
            + "AND h.setupIntentVerifiedAt IS NULL "
            + "AND (h.acceptedAt <= :cutoff "
            + "     OR (c.currentPeriodEnd IS NOT NULL AND c.currentPeriodEnd <= :periodEndCutoff)) "
            + "AND (h.acceptedAt > :afterAcceptedAt "
            + "     OR (h.acceptedAt = :afterAcceptedAt AND h.id > :afterId)) "
            + "ORDER BY h.acceptedAt, h.id")
    List<Object[]> findStalledSwitchingPage(@Param("status") PayerHandoverStatus status,
                                            @Param("cutoff") Instant cutoff,
                                            @Param("periodEndCutoff") LocalDateTime periodEndCutoff,
                                            @Param("afterAcceptedAt") Instant afterAcceptedAt,
                                            @Param("afterId") UUID afterId,
                                            Pageable pageable);

    /**
     * {@code FAILED} で終端したが<b>Stripe の後始末が未了</b>の引継要求 ID を返す
     * （PR-4 Codex 検分3巡目 P1-2）。
     *
     * <p><b>何を回収するか</b>: 失敗確定の経路は「CAS で権利を取る → Stripe の新サブスク取消と
     * 旧サブスク差し戻し → 後始末完了の記録」の3段で進む（外部副作用より先に fencing を取るため
     * この順序でなければならない）。2段目が失敗すると、要求は終端済みなのに
     * <b>旧試行の新サブスクが Stripe に残り、旧サブスクは期末解約予約のまま</b>になる。
     * そのまま放置すると、別 ADMIN の次の承諾で作られる新サブスクとの二重サブスクになり得る。</p>
     *
     * <p><b>目印は {@code old_cancel_scheduled_at}</b>: 後始末が完了した時点でこの列を NULL へ
     * クリアする（差し戻しと対の操作・§3.6.1 R5-P2）。したがって
     * 「{@code FAILED} なのに NULL でない」は<b>後始末が未了であることの証跡</b>そのものである。
     * 新しい状態も列も増やさずに、回収可能性を確保している。</p>
     */
    @Query("SELECT h.id FROM BillingPayerHandoverRequestEntity h "
            + "WHERE h.status = :status "
            + "AND h.oldCancelScheduledAt IS NOT NULL "
            + "ORDER BY h.requestedAt")
    List<UUID> findFailedWithPendingCleanupIds(@Param("status") PayerHandoverStatus status);

    /**
     * 猶予期限を過ぎたまま {@code ACCEPTED} に留まり、新サブスク参照が未確定の引継要求 ID を返す
     * （設計書 §5.3・§3.6.1(a) の照合対象と同じ形・Codex検分4巡目 P1）。
     *
     * <p><b>なぜこの抽出が要るか</b>: 承諾後の Stripe List 照会が失敗すると、その承諾者の Customer に
     * 新サブスクが在るか不明なまま {@code ACCEPTED} が残る（承諾者を固定して本人の再試行に委ねる設計）。
     * 承諾者が戻らないとこの行は<b>誰にも触られず永久に残り</b>、非終端であるため §5.4 により purge の
     * 期末解約フォールバックはスキップされ続け、生成列 + UNIQUE により同一旧契約への再要求も
     * ブロックされ続ける。結果として「旧 payer の課金を止める」という本機能の目的が果たせない。</p>
     *
     * <p>{@code psp_new_subscription_ref IS NULL} を条件に含めるのは、参照が確定済みの行は
     * 曖昧ではなく (a)引継確定条件の到来を待っている正常な進行中だからである。</p>
     *
     * @param status 通常 {@link PayerHandoverStatus#ACCEPTED}
     * @param now    現在時刻（{@code expires_at} と同じ {@code Instant}）
     */
    @Query("SELECT h.id FROM BillingPayerHandoverRequestEntity h "
            + "WHERE h.status = :status "
            + "AND h.pspNewSubscriptionRef IS NULL "
            + "AND h.expiresAt <= :now "
            + "ORDER BY h.requestedAt")
    List<UUID> findExpiredUnresolvedAcceptedIds(@Param("status") PayerHandoverStatus status,
                                                @Param("now") Instant now);
}
