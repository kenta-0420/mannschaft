package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.MfaRecoveryTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * MFAリカバリートークンリポジトリ。
 */
public interface MfaRecoveryTokenRepository extends JpaRepository<MfaRecoveryTokenEntity, Long> {

    Optional<MfaRecoveryTokenEntity> findByTokenHash(String tokenHash);

    /**
     * 回復トークンの所有者 ID だけをスカラー射影で引く（エンティティを永続コンテキストへ載せない）。
     *
     * <p>回復確認でロック順を「2FA 行 → 回復トークン行」に揃えるため、ロック前に所有者を知る用途。
     * エンティティを読み込まないので、後続の {@link #findByTokenHashForUpdate} が一次キャッシュの古い写しを返さない。</p>
     *
     * @param tokenHash トークンの SHA-256
     * @return 所有者のユーザー ID
     */
    @Query("select t.userId from MfaRecoveryTokenEntity t where t.tokenHash = :tokenHash")
    Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 悲観ロック（{@code PESSIMISTIC_WRITE}）付きで回復トークンを取得する（CMP-261012-0910 AC-48）。
     *
     * <p>同一トークンの並列確認を DB 行ロックで直列化する。後着側はロック解放後に最新の {@code used_at} を
     * 読むため、二重にログインが成立しない。</p>
     *
     * @param tokenHash トークンの SHA-256
     * @return ロック取得済みの回復トークン
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MfaRecoveryTokenEntity t where t.tokenHash = :tokenHash")
    Optional<MfaRecoveryTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * 指定ユーザーの未使用の回復トークン（CMP-261012-0910 AC-48。再要求・無効化・再設定時に失効させる対象）。
     *
     * @param userId ユーザー ID
     * @return 未使用（{@code used_at IS NULL}）のトークン
     */
    List<MfaRecoveryTokenEntity> findByUserIdAndUsedAtIsNull(Long userId);

    /**
     * 指定ユーザーのMFAリカバリートークンを全件物理削除する（GDPR退会バッチ用）。
     *
     * @param userId 削除対象ユーザーID
     */
    @Modifying
    void deleteByUserId(Long userId);
}
