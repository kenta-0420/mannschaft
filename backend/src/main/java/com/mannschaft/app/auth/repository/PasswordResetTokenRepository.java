package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.PasswordResetTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

/**
 * パスワードリセットトークンリポジトリ。
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, Long> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    /**
     * 指定ユーザーのパスワードリセットトークンを全件物理削除する（GDPR退会バッチ用）。
     *
     * @param userId 削除対象ユーザーID
     */
    @Modifying
    void deleteByUserId(Long userId);
}
