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
}
