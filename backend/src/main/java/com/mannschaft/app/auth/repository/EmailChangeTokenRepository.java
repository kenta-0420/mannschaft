package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.EmailChangeTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

/**
 * メールアドレス変更トークンリポジトリ。
 */
public interface EmailChangeTokenRepository extends JpaRepository<EmailChangeTokenEntity, Long> {

    Optional<EmailChangeTokenEntity> findByTokenHash(String tokenHash);

    /**
     * 指定ユーザーのメールアドレス変更トークンを全件物理削除する（GDPR退会バッチ用）。
     *
     * @param userId 削除対象ユーザーID
     */
    @Modifying
    void deleteByUserId(Long userId);
}
