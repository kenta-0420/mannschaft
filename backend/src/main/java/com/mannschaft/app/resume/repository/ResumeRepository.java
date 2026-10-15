package com.mannschaft.app.resume.repository;

import com.mannschaft.app.resume.entity.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 履歴書バージョン リポジトリ（F01.10）。
 *
 * <p>個人スコープのドメインにつき {@code AbstractTenantAwareRepository} は使用しない。
 * {@code user_id} で絞り込む。
 *
 * <p>GDPR 退会パージ処理のために {@link #findAllByUserId(Long)} と
 * {@link #deletePhysicallyByUserId(Long)} を提供する。
 * 証明写真（{@code photo_key}）のストレージ削除は Service 層で
 * {@link #findAllByUserId(Long)} で列挙してから実行すること。
 */
public interface ResumeRepository extends JpaRepository<ResumeEntity, UUID> {

    /**
     * ユーザーの生存中（論理削除除外）の履歴書一覧を取得する。
     *
     * <p>{@link com.mannschaft.app.resume.entity.ResumeEntity} の {@code @SQLRestriction}
     * により {@code deleted_at IS NULL} が自動付加される。
     */
    List<ResumeEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * ユーザーの特定の履歴書を取得する（本人確認用）。
     *
     * <p>Service 層でオーナー確認（{@code userId} が一致するか）に使用する。
     */
    Optional<ResumeEntity> findByIdAndUserId(UUID id, Long userId);

    /**
     * GDPR 退会パージ: ユーザーの全履歴書レコードを列挙する（{@code deleted_at} 問わず）。
     *
     * <p>photo_key を列挙してストレージ削除する目的で使用する。
     * {@link #deletePhysicallyByUserId(Long)} の前にこのメソッドで photo_key を収集すること。
     */
    @Query("SELECT r FROM ResumeEntity r WHERE r.userId = :userId")
    List<ResumeEntity> findAllByUserId(@Param("userId") Long userId);

    /**
     * GDPR 退会パージ: ユーザーの全履歴書レコードを物理削除する（{@code deleted_at} 問わず）。
     *
     * <p>カスケード削除（ON DELETE CASCADE）により子テーブル（educations / careers /
     * qualifications / skills）も自動的に物理削除される。
     * 実行前に {@link #findAllByUserId(Long)} で photo_key を収集し、
     * ストレージ削除を行ってから本メソッドを呼ぶこと。
     */
    @Modifying
    @Query("DELETE FROM ResumeEntity r WHERE r.userId = :userId")
    void deletePhysicallyByUserId(@Param("userId") Long userId);
}
