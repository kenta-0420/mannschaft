package com.mannschaft.app.navsettings.repository;

import com.mannschaft.app.navsettings.entity.UserNavSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNavSettingsRepository extends JpaRepository<UserNavSettingsEntity, Long> {

    /**
     * 非表示キー（hidden_nav_keys）と個人並び順（nav_display_order）を1行で UPSERT する。
     * navDisplayOrderJson が null の場合はマスタ sort_order 順にリセットされる。
     */
    @Modifying
    @Query(value = """
        INSERT INTO user_nav_settings (user_id, hidden_nav_keys, nav_display_order, updated_at)
        VALUES (:userId, :hiddenNavKeysJson, :navDisplayOrderJson, NOW(6))
        ON DUPLICATE KEY UPDATE
            hidden_nav_keys = :hiddenNavKeysJson,
            nav_display_order = :navDisplayOrderJson,
            updated_at = NOW(6)
        """, nativeQuery = true)
    void upsertSettings(@Param("userId") Long userId,
                        @Param("hiddenNavKeysJson") String hiddenNavKeysJson,
                        @Param("navDisplayOrderJson") String navDisplayOrderJson);
}
