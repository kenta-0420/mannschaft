package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
