package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * F09.17 受信者ごとの広告受信設定リポジトリ。
 * 各ユーザー 1 行で UNIQUE 制約付き。
 */
public interface UserAdPreferenceRepository extends JpaRepository<UserAdPreference, UUID> {

    /** ユーザー単位の設定取得 (UNIQUE)。 */
    Optional<UserAdPreference> findByUserId(Long userId);

    /** 設定有無の高速判定。 */
    boolean existsByUserId(Long userId);

    /** 退会時の物理削除 (個人設定は保持価値が無いため)。 */
    void deleteByUserId(Long userId);

    /**
     * 退会30日後の強匿名化専用。所有列 {@code user_id} で本人の行を一括物理削除する（行ごとのループを行わない）。
     *
     * @param userId 完全削除された利用者 ID
     * @return 削除件数
     */
    @Modifying
    @Query(value = "DELETE FROM user_ad_preferences WHERE user_id = :userId", nativeQuery = true)
    int deleteAllOwnedByUserId(@Param("userId") Long userId);
}
