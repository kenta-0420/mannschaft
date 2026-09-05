package com.mannschaft.app.billing;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * @param status 通常 {@link PayerHandoverStatus#SWITCHING}
     * @param now    現在時刻（{@code billing_contracts} の壁時計へ変換済み）
     */
    @Query("SELECT h.id FROM BillingPayerHandoverRequestEntity h "
            + "JOIN BillingContractEntity c ON c.id = h.oldContractId "
            + "WHERE h.status = :status "
            + "AND c.currentPeriodEnd IS NOT NULL AND c.currentPeriodEnd <= :now "
            + "ORDER BY h.requestedAt")
    List<UUID> findSwitchDueIds(@Param("status") PayerHandoverStatus status,
                                @Param("now") LocalDateTime now);
}
