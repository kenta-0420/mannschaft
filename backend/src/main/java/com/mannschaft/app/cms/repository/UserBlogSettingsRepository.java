package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.UserBlogSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ユーザーブログ設定リポジトリ。
 */
public interface UserBlogSettingsRepository extends JpaRepository<UserBlogSettingsEntity, Long> {

    Optional<UserBlogSettingsEntity> findByUserId(Long userId);
}
