package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.UserBlogSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * ユーザーブログ設定リポジトリ。
 */
public interface UserBlogSettingsRepository extends JpaRepository<UserBlogSettingsEntity, Long> {

    Optional<UserBlogSettingsEntity> findByUserId(Long userId);

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM user_blog_settings WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") Long userId);
}
