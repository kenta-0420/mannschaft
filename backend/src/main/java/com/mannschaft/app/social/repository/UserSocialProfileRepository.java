package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.entity.UserSocialProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ソーシャルプロフィールリポジトリ。
 */
public interface UserSocialProfileRepository extends JpaRepository<UserSocialProfileEntity, Long> {

    /**
     * ユーザーIDでプロフィールを取得する。
     */
    Optional<UserSocialProfileEntity> findByUserId(Long userId);

    /**
     * ハンドルでプロフィールを取得する。
     */
    Optional<UserSocialProfileEntity> findByHandle(String handle);

    /**
     * ハンドルの存在チェックを行う。
     */
    boolean existsByHandle(String handle);

    /**
     * ユーザーIDの存在チェックを行う。
     */
    boolean existsByUserId(Long userId);
}
