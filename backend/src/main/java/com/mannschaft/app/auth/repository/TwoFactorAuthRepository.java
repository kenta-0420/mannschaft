package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 二要素認証リポジトリ。
 */
public interface TwoFactorAuthRepository extends JpaRepository<TwoFactorAuthEntity, Long> {

    Optional<TwoFactorAuthEntity> findByUserId(Long userId);

    /**
     * 悲観ロック（{@code PESSIMISTIC_WRITE}）付きでユーザーの 2FA 設定を取得する（CMP-261012-0910 AC-9）。
     *
     * <p>ログイン時のバックアップコード消費はこのメソッドで行を取ってから照合・削除する。同一コードの並列提示で
     * 両者が古い JSON の写しを読んで共に通ることを、DB 行ロックで直列化して防ぐ。同一 TX 内で先に
     * {@link #findByUserId} を呼ぶと一次キャッシュの古い写しが返るため、最初の読取りとして呼ぶこと。</p>
     *
     * @param userId ユーザー ID
     * @return ロック取得済みの 2FA 設定
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TwoFactorAuthEntity t where t.userId = :userId")
    Optional<TwoFactorAuthEntity> findByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * ユーザーIDに紐づく2FA設定を削除する（退会匿名化用）。
     */
    void deleteByUserId(Long userId);

    /**
     * 「強い 2FA」（TOTP 方式の 2FA が有効、または WebAuthn 資格情報が登録済み）を持つかを 1 クエリで判定する
     * （CMP-261012-0910 AC-47。オーナー移譲の 2FA 要件。メールOTP 方式は含めない）。
     *
     * <p>2FA の強度は two_factor_auth と webauthn_credentials（いずれも auth ドメイン）にまたがる判定のため、
     * 判定そのものを本リポジトリの 1 クエリに閉じる。呼び出し元（role の移譲 TX）が到達する auth の
     * リポジトリを増やさないためでもある（D-3T。role → auth.TwoFactorAuthRepository は既存の凍結済み越境）。</p>
     *
     * @param userId ユーザー ID
     * @return 強い 2FA を持つなら 1、持たないなら 0
     */
    @Query(value = "SELECT CASE WHEN EXISTS ("
            + "SELECT 1 FROM two_factor_auth t WHERE t.user_id = :userId AND t.is_enabled = 1"
            + " AND t.method = 'TOTP' AND t.totp_secret IS NOT NULL"
            + ") OR EXISTS ("
            + "SELECT 1 FROM webauthn_credentials w WHERE w.user_id = :userId"
            + ") THEN 1 ELSE 0 END", nativeQuery = true)
    long countStrongSecondFactor(@Param("userId") Long userId);
}
