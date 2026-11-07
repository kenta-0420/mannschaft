package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * リフレッシュトークンリポジトリ。
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    /** 署名検証済み本人の単一セッションだけをログアウト対象として取得する。 */
    Optional<RefreshTokenEntity> findByTokenHashAndUserId(String tokenHash, Long userId);

    /**
     * 悲観ロック（{@code PESSIMISTIC_WRITE}）付きでリフレッシュトークンを取得する。
     *
     * <p>リフレッシュトークンのローテーションはこのメソッドで取得することで、同一トークンに対する
     * 並行 refresh を DB 行ロックで直列化する。これにより「片方が revoke → もう片方が使用済みトークンを
     * 再提示 → リプレイ誤判定 → 全セッション無効化」という自爆を根本から防ぐ。</p>
     *
     * @param tokenHash リフレッシュトークンの SHA-256 ハッシュ
     * @return ロック取得済みのトークン（存在しなければ empty）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshTokenEntity r where r.tokenHash = :tokenHash")
    Optional<RefreshTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RefreshTokenEntity> findByUserIdAndRevokedAtIsNull(Long userId);

    /**
     * 指定ユーザーの有効な Refresh Token を一括で失効させる（CMP-261012-0910 MFA 回復確認の全セッション失効）。
     *
     * <p>一括 UPDATE は InnoDB の current read になるため、同じ TX の読取りビューが行ロック待ちの前に固定されていても、
     * 待っている間に他 TX（ログイン等）が commit したトークンまで失効できる。{@link #findByUserIdAndRevokedAtIsNull}
     * で列挙して revoke する方式は、固定済みビューから見えない行を取りこぼす。保留中の変更は実行前に flush する。</p>
     *
     * @param userId ユーザー ID
     * @param now    失効時刻
     * @return 失効させた件数
     */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshTokenEntity r set r.revokedAt = :now where r.userId = :userId and r.revokedAt is null")
    int revokeAllActiveByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    void deleteByExpiresAtBefore(LocalDateTime dateTime);

    /**
     * 指定ユーザーの直近N日以内に同一IP+デバイスフィンガープリントのトークンが存在するか確認する。
     * 新規デバイスログイン検知に使用（F12.4 §5.5）。
     */
    boolean existsByUserIdAndIpAddressAndDeviceFingerprintAndCreatedAtAfter(
            Long userId, String ipAddress, String deviceFingerprint, LocalDateTime since);

    /**
     * 指定ユーザーのアクティブセッション数を返す（セッション上限チェック用、F12.4 §5.7）。
     */
    long countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(Long userId, LocalDateTime now);

    /**
     * 指定ユーザーのトークン総数を返す（初回ログイン判定用、F12.4 §5.5）。
     */
    long countByUserId(Long userId);
}
