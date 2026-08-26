package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
