package com.mannschaft.app.quickmemo.repository;

import com.mannschaft.app.quickmemo.entity.UserVoiceInputConsentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 音声入力同意履歴リポジトリ。
 */
public interface UserVoiceInputConsentRepository extends JpaRepository<UserVoiceInputConsentEntity, Long> {

    /**
     * ユーザーの指定バージョンで有効な同意（取消なし）を取得する。
     */
    Optional<UserVoiceInputConsentEntity> findByUserIdAndVersionAndRevokedAtIsNull(
            Long userId, Integer version);

    /**
     * ユーザーの同意履歴をバージョン降順で取得する。
     */
    List<UserVoiceInputConsentEntity> findByUserIdOrderByVersionDesc(Long userId);

    /**
     * ユーザーの有効な同意が存在するか確認する。
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN TRUE ELSE FALSE END " +
           "FROM UserVoiceInputConsentEntity c " +
           "WHERE c.userId = :userId AND c.version = :version AND c.revokedAt IS NULL")
    boolean hasActiveConsent(@Param("userId") Long userId, @Param("version") Integer version);
}
