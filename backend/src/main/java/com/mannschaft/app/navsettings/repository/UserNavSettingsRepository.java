package com.mannschaft.app.navsettings.repository;

import com.mannschaft.app.navsettings.entity.UserNavSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNavSettingsRepository extends JpaRepository<UserNavSettingsEntity, Long> {

    @Modifying
    @Query(value = """
        INSERT INTO user_nav_settings (user_id, hidden_nav_keys, updated_at)
        VALUES (:userId, :hiddenNavKeysJson, NOW(6))
        ON DUPLICATE KEY UPDATE hidden_nav_keys = :hiddenNavKeysJson, updated_at = NOW(6)
        """, nativeQuery = true)
    void upsertHiddenKeys(@Param("userId") Long userId,
                          @Param("hiddenNavKeysJson") String hiddenNavKeysJson);
}
