package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ユーザー×カレンダーレイヤー表示設定リポジトリ（F03.19）。
 *
 * <p>設計書 §4.3〜4.5 の口に対応する finder のみを定義する。
 * 本表は本人のみが読み書きできる個人設定であり（{@code user_id} が常に呼び出し本人と一致することを
 * Service 層が保証する）、{@code AbstractTenantAwareRepository} の適用対象外（設計書 §3.1）。</p>
 */
public interface UserCalendarLayerSettingRepository extends JpaRepository<UserCalendarLayerSettingEntity, UUID> {

    /** §4.3 レイヤー一覧の合成に使う、本人の設定行の全件取得。 */
    List<UserCalendarLayerSettingEntity> findByUserId(Long userId);

    /** §4.4/4.5 の upsert・削除キー（{@code uk_user_calendar_layer} と同一）。 */
    Optional<UserCalendarLayerSettingEntity> findByUserIdAndScopeTypeAndScopeId(
            Long userId, String scopeType, Long scopeId);

    /** §4.5 DELETE（冪等・物理削除）。 */
    void deleteByUserIdAndScopeTypeAndScopeId(Long userId, String scopeType, Long scopeId);

    /**
     * §4.4 PATCH の<b>原子的な行作成</b>（{@code uk_user_calendar_layer} 競合を例外にしない）。
     *
     * <p>「{@code findBy...} が空 → 新規 Entity を作って {@code save}」は<b>検査と書き込みの間に
     * 隙間がある</b>ため、設定行がまだ無い同一レイヤーへ PATCH が並行して 2 件来ると
     * 両方が新規行を作ろうとし、片方が {@code uk_user_calendar_layer} 違反で 500 を返す。
     * PATCH は upsert・冪等を契約しているので、これは契約違反である。</p>
     *
     * <p><b>なぜ例外捕捉（{@code save} + {@code DataIntegrityViolationException} でリトライ）ではなく
     * {@code INSERT IGNORE} か</b>: 制約違反が例外化された時点で現在のトランザクションは
     * rollback-only になり、同じトランザクション内で取り直して更新に回すことができない
     * （{@code AdCampaignDeliveryClaimRepository} で同じ結論に達している）。
     * {@code INSERT IGNORE} は競合そのものを例外化しないため、呼び出し元のトランザクションを
     * 汚さずに「無ければ作る・有ればそのまま」を 1 往復で確定できる。</p>
     *
     * <p>作るのは<b>既定値の行</b>（{@code color=NULL}＝自動色 / {@code hidden=FALSE}）だけである。
     * 実際の値の反映は呼び出し元が JPA の更新で行う（部分更新の意味論はサービス層に残す）。</p>
     *
     * @param id       採番済み UUIDv7 の 16 バイト表現（{@code BINARY(16)}）
     * @param userId   設定の所有者
     * @param scopeType レイヤー種別
     * @param scopeId  レイヤー対象ID
     * @return 挿入できたら 1、既に行が存在して無視されたら 0
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO user_calendar_layer_settings "
            + "(id, user_id, scope_type, scope_id, color, hidden, created_at, updated_at) "
            + "VALUES (:id, :userId, :scopeType, :scopeId, NULL, FALSE, NOW(3), NOW(3))",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") byte[] id,
                       @Param("userId") Long userId,
                       @Param("scopeType") String scopeType,
                       @Param("scopeId") Long scopeId);

    /**
     * §4.4 PATCH で {@link #insertIfAbsent} が 0 件を返したとき、<b>先着がコミットした行を取り直す</b>ための
     * ロック付き読み取り（{@code SELECT ... FOR UPDATE}）。
     *
     * <p><b>なぜ通常の {@code findBy...} では駄目か</b>: 本番 MySQL の分離レベルは
     * {@code REPEATABLE-READ}（既定のまま。実 DB の
     * {@code @@global.transaction_isolation} / {@code @@session.transaction_isolation} で確認済み）である。
     * REPEATABLE READ の<b>通常の SELECT は一貫性読み取り</b>で、トランザクション最初の読み取りが
     * 張ったスナップショットを見続ける。つまり
     * 「{@code findBy...} が空 → {@code INSERT IGNORE} が 0（＝先着が居る）→ もう一度 {@code findBy...}」
     * と進んでも、<b>先着の行は最後まで見えない</b>。前段の修正はこの点を見落としており、
     * 0 件挿入の分岐が {@code IllegalStateException}（＝ 500）に落ちて、
     * 塞いだはずの並行 PATCH の 500 が残っていた。</p>
     *
     * <p>{@code FOR UPDATE} などのロック付き読み取りは InnoDB では<b>現在読み取り
     * （current read）</b>になり、スナップショットではなく<b>最新のコミット済みバージョン</b>を読む。
     * よって先着の行が確実に見え、そのまま部分更新（{@code null}＝変更しない）の意味論を
     * Java 側に残したまま更新に回せる。値まで含めた {@code ON DUPLICATE KEY UPDATE} に寄せると
     * 部分更新の意味論を SQL に持ち込むことになるため採らない
     * （兄弟の {@code UserCalendarSyncSettingRepository#upsert} は全項目上書きなので
     * 読み直しが不要であり、この問題を持たない）。</p>
     *
     * <p>ロックは {@code INSERT IGNORE} が 0 を返した直後（＝先着は既にコミット済み）にのみ取り、
     * 取得後すぐ更新してトランザクションを閉じるため、保持時間は短い。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UserCalendarLayerSettingEntity s "
            + "WHERE s.userId = :userId AND s.scopeType = :scopeType AND s.scopeId = :scopeId")
    Optional<UserCalendarLayerSettingEntity> findForUpdateByUserIdAndScopeTypeAndScopeId(
            @Param("userId") Long userId,
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId);

    /** §4.4 の行数上限（1000件未満）チェック用。 */
    long countByUserId(Long userId);

    /**
     * §10.4【R9】チーム／組織の<b>削除</b>に伴う後始末。
     *
     * <p>当該スコープの設定行を<b>全ユーザー分</b>物理削除する。
     * <b>脱退では呼ばない</b>（脱退時は行を残し、再加入で色が復活する — R9）。</p>
     *
     * @return 削除した行数
     */
    @Modifying
    @Query("DELETE FROM UserCalendarLayerSettingEntity s "
            + "WHERE s.scopeType = :scopeType AND s.scopeId = :scopeId")
    int deleteByScopeTypeAndScopeId(@Param("scopeType") String scopeType,
                                    @Param("scopeId") Long scopeId);

    /**
     * §10.4【R9】ユーザー退会（即時匿名化）に伴う後始末。
     *
     * <p>当該ユーザーの設定行を<b>全スコープ分</b>物理削除する。色設定は個人の嗜好情報であり、
     * 匿名化後のユーザーに紐づけて残す意味が無い。</p>
     *
     * @return 削除した行数
     */
    @Modifying
    @Query("DELETE FROM UserCalendarLayerSettingEntity s WHERE s.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
