package com.mannschaft.app.contact.repository;

import com.mannschaft.app.contact.entity.ContactInviteTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 連絡先招待トークンリポジトリ。
 */
public interface ContactInviteTokenRepository extends JpaRepository<ContactInviteTokenEntity, Long> {

    /** トークン文字列で取得 */
    Optional<ContactInviteTokenEntity> findByToken(String token);

    /** 発行者の有効トークン一覧（無効化済みを除く） */
    List<ContactInviteTokenEntity> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(Long userId);

    /** IDと発行者IDで取得（オーナーチェック） */
    Optional<ContactInviteTokenEntity> findByIdAndUserId(Long id, Long userId);
}
