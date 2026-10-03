package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.reflection.entity.UserReflectionSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * {@link UserReflectionSettingsEntity} のリポジトリ（F06.5・§2.7）。
 *
 * <p>自然キー（user_id BIGINT）のため {@code JpaRepository<..., Long>}。未設定ユーザーは既定値で扱う。</p>
 */
@Repository
public interface UserReflectionSettingsRepository
        extends JpaRepository<UserReflectionSettingsEntity, Long> {
}
