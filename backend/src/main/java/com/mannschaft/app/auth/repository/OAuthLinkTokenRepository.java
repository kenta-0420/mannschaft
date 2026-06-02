package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.OAuthLinkTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

/**
 * OAuthアカウント連携トークンリポジトリ。
 */
public interface OAuthLinkTokenRepository extends JpaRepository<OAuthLinkTokenEntity, Long> {

    Optional<OAuthLinkTokenEntity> findByTokenHash(String tokenHash);

    /**
     * 指定ユーザーのOAuth連携トークンを全件物理削除する（GDPR退会バッチ用）。
     *
     * @param userId 削除対象ユーザーID
     */
    @Modifying
    void deleteByUserId(Long userId);
}
