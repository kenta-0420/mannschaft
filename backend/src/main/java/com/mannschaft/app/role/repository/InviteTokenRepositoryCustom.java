package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.dto.InviteClaimAuditContext;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.dto.InviteTokenSnapshot;
import com.mannschaft.app.role.entity.InviteTokenEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * 招待トークンと確保記録（{@code invite_token_claims}）の、派生クエリでは書けない操作（カスタム fragment）。
 *
 * <p>使用回数（{@code used_count}）を変えてよいのは {@link #incrementUsedCountIfAvailable(Long)}（確保）と
 * {@link #decrementUsedCount(Long)}（補償）だけである。native 文を実行した後は管理下の
 * {@link InviteTokenEntity} を {@link #refreshIfManaged(Long)} で最新化する
 * （{@code clearAutomatically} は登録中のユーザー Entity を巻き込むため使わない）。</p>
 */
public interface InviteTokenRepositoryCustom {

    /** 招待の所属先を、トークンのハッシュで行ロックなしの通常読みで引く。 */
    Optional<InviteScopeRef> findScopeByTokenHash(String tokenHash);

    /** 招待の所属先を、招待 ID で行ロックなしの通常読みで引く。 */
    Optional<InviteScopeRef> findScopeById(Long id);

    /** 招待行をトークンのハッシュで {@code FOR UPDATE} で読み、最新値を Entity を介さず射影で返す。 */
    Optional<InviteTokenSnapshot> lockSnapshotByTokenHash(String tokenHash);

    /** {@link #lockSnapshotByTokenHash(String)} の招待 ID 版。 */
    Optional<InviteTokenSnapshot> lockSnapshotById(Long id);

    /**
     * 未失効かつ上限未満のときだけ使用回数を1進める条件付き UPDATE。
     *
     * @return 更新行数（1: 成功 / 0: 失効または上限到達）
     */
    int incrementUsedCountIfAvailable(Long tokenId);

    /** 使用回数を1戻す（0 を下回らない）。補償専用。 @return 更新行数 */
    int decrementUsedCount(Long tokenId);

    /** 同じトークンの Entity を最新状態へ再読込する（native 文の後に呼ぶ）。 */
    void refreshIfManaged(Long tokenId);

    /** 外側トランザクションのスナップショットや一次キャッシュに左右されず、最新値で行をロックして返す。 */
    Optional<InviteTokenEntity> lockFreshById(Long id);

    /** {@link #lockFreshById(Long)} のトークンハッシュ版。 */
    Optional<InviteTokenEntity> lockFreshByTokenHash(String tokenHash);

    /** CLAIMED の確保記録を1行挿入して主キー（UUIDv7）を返す。 */
    UUID insertClaim(Long tokenId, Long userId);

    /** CLAIMED→GRANTED の条件付き UPDATE。 @return 更新行数 */
    int markClaimGranted(UUID claimId);

    /** CLAIMED→RELEASED の条件付き UPDATE。 @return 更新行数 */
    int markClaimReleased(UUID claimId);

    /** claim が指す招待の主キー。 */
    Optional<Long> findClaimTokenId(UUID claimId);

    /** claim の現在状態を、ロック付きの最新値で読む。 */
    Optional<String> findClaimStatusLocked(UUID claimId);

    /** claim と招待行を突き合わせ、招待監査イベントの材料を返す。 */
    Optional<InviteClaimAuditContext> findClaimAuditContext(UUID claimId);

    /** 作成から閾値秒を超えて CLAIMED のままの件数（監視用）。 */
    long countStaleClaimed(long thresholdSeconds);

    /** 最古の CLAIMED の滞留秒。CLAIMED が無ければ 0（監視用）。 */
    long oldestClaimedAgeSeconds();
}
