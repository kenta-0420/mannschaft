package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.EmailVerificationTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * メール認証トークンリポジトリ。
 */
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationTokenEntity, Long> {

    Optional<EmailVerificationTokenEntity> findByTokenHash(String tokenHash);

    // 指定ユーザーIDのトークンを全物理削除（ユーザー論理削除前の紐付け解消用）
    void deleteByUserIdIn(List<Long> userIds);

    // 期限切れかつ未使用のトークンを全物理削除（残骸クリーンアップ用）
    void deleteByExpiresAtBeforeAndUsedAtIsNull(LocalDateTime threshold);
}
