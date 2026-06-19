package com.mannschaft.app.notification.credit.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 組織別通知クレジット残高エンティティ。
 *
 * <p>組織ごとに1行のみ存在する。
 * {@code @Version} による楽観的ロックと {@code PESSIMISTIC_WRITE} を組み合わせて並行制御する。</p>
 */
@Entity
@Table(name = "organization_notification_balances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class OrganizationNotificationBalanceEntity extends BaseEntity {

    /** 組織ID（UNIQUE制約） */
    @Column(nullable = false, unique = true)
    private Long organizationId;

    /** 今月の無料枠使用通数 */
    @Column(nullable = false)
    @SuperBuilder.Default
    private Long freeUsedThisMonth = 0L;

    /** 無料枠リセット月（YYYY-MM-01） */
    @Column(nullable = false)
    private LocalDate freeQuotaMonth;

    /** 今月の9000通アラート送信済みフラグ */
    @Column(nullable = false)
    @SuperBuilder.Default
    private Boolean alertSentThisMonth = false;

    /**
     * クレジット残高（マイナスあり: 残高不足時は負債）。
     */
    @Column(nullable = false)
    @SuperBuilder.Default
    private Long creditBalance = 0L;

    /** 残高不足による猶予期間開始日時 */
    private LocalDateTime gracePeriodStartAt;

    /**
     * 猶予期間中の累積負債（翌月1日に {@code credit_balance} から相殺する）。
     */
    @Column(nullable = false)
    @SuperBuilder.Default
    private Long gracePeriodDebt = 0L;

    /** JPA楽観的ロック用バージョン */
    @Version
    @Column(nullable = false)
    @SuperBuilder.Default
    private Long version = 0L;

    // ─────────────────────────────────────────────────────────
    // ビジネスメソッド
    // ─────────────────────────────────────────────────────────

    /**
     * 無料枠から通数を消費する。
     *
     * @param count 消費通数
     */
    public void consumeFree(long count) {
        this.freeUsedThisMonth += count;
    }

    /**
     * クレジット残高から通数を消費する。
     *
     * @param count 消費通数
     */
    public void consumeCredit(long count) {
        this.creditBalance -= count;
    }

    /**
     * 猶予期間を開始する。
     *
     * @param debt 初回負債通数
     */
    public void startGracePeriod(long debt) {
        this.gracePeriodStartAt = LocalDateTime.now();
        this.gracePeriodDebt += debt;
    }

    /**
     * 猶予期間中に追加負債を積み上げる。
     *
     * @param debt 追加負債通数
     */
    public void addGraceDebt(long debt) {
        this.gracePeriodDebt += debt;
    }

    /**
     * クレジットを加算する（購入完了時）。
     *
     * @param amount 加算するクレジット通数
     */
    public void addCredits(long amount) {
        this.creditBalance += amount;
    }

    /**
     * 9000通アラート送信済みとしてマークする。
     */
    public void markAlertSentThisMonth() {
        this.alertSentThisMonth = true;
    }

    /**
     * 無料枠カウンタを指定月向けにリセットする（バッチ未実行補完）。
     *
     * <p>月初バッチが走る前に当月最初の送信が来たとき、{@code consume} から呼ばれる。
     * {@link #monthlyReset} と異なり猶予期間負債の残高相殺（{@code creditBalance -= gracePeriodDebt}）は
     * 行わず、無料枠カウンタ・当月アラート・猶予負債のみを当月用に初期化する（従来挙動を踏襲）。</p>
     *
     * <p><strong>なぜ builder ({@code toBuilder().build()}) で作り直さないか:</strong>
     * 本エンティティは {@code @SuperBuilder(toBuilder = true)}（{@code @SuperBuilder} ではない）で、
     * 主キー {@code id} は基底クラス {@link BaseEntity} のフィールドである。
     * {@code toBuilder()} は継承フィールド {@code id} を引き継がず {@code id = null} の
     * 新インスタンスになり、{@code save} が UPDATE でなく INSERT を実行して
     * {@code organization_id} 一意制約違反で 500 になる。よって直接ミューテートする。</p>
     *
     * @param currentMonth 今月1日の日付
     */
    public void resetFreeQuotaForMonth(LocalDate currentMonth) {
        this.freeUsedThisMonth = 0L;
        this.freeQuotaMonth = currentMonth;
        this.alertSentThisMonth = false;
        this.gracePeriodDebt = 0L;
    }

    /**
     * 月次リセット処理（毎月1日バッチが実行）。
     * 猶予期間の負債を相殺し、無料枠カウンタをリセットする。
     *
     * @param currentMonth 今月1日の日付
     */
    public void monthlyReset(LocalDate currentMonth) {
        // 猶予期間負債を残高から相殺
        this.creditBalance -= this.gracePeriodDebt;
        // 無料枠カウンタリセット
        this.freeUsedThisMonth = 0L;
        this.freeQuotaMonth = currentMonth;
        this.alertSentThisMonth = false;
        this.gracePeriodStartAt = null;
        this.gracePeriodDebt = 0L;
    }
}
