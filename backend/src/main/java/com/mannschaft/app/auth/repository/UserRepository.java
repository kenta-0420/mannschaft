package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ユーザーリポジトリ。
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    /** @ハンドルでユーザーを取得する（F04.8 連絡先機能）。 */
    Optional<UserEntity> findByContactHandle(String contactHandle);

    /** @ハンドルの使用有無を確認する（自分以外）。 */
    boolean existsByContactHandleAndIdNot(String contactHandle, Long excludeId);

    /** @ハンドルの使用有無を確認する（全ユーザー）。 */
    boolean existsByContactHandle(String contactHandle);

    List<UserEntity> findByStatusAndCreatedAtBefore(UserStatus status, LocalDateTime threshold);

    /**
     * ユーザーIDコレクションから一括取得する（N+1 防止）。
     *
     * <p>F03.12 §14 主催者点呼候補者一覧取得時に、表示名・アバターを 1 クエリで解決するために使用する。</p>
     *
     * @param ids ユーザーID コレクション
     * @return 該当ユーザー一覧（@SQLRestriction により未削除のみ）
     */
    List<UserEntity> findByIdIn(Collection<Long> ids);

    boolean existsByEmail(String email);

    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE u.displayName LIKE %:keyword% OR u.email LIKE %:keyword%")
    java.util.List<UserEntity> searchByKeyword(@org.springframework.data.repository.query.Param("keyword") String keyword, org.springframework.data.domain.Pageable pageable);

    long countByStatus(UserEntity.UserStatus status);

    long countByLastLoginAtAfterAndStatusAndDeletedAtIsNull(LocalDateTime since, UserEntity.UserStatus status);

    // === Analytics 集計用クエリ ===

    /**
     * 指定日に作成されたユーザー数を取得する。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(u) FROM UserEntity u WHERE CAST(u.createdAt AS localdate) = :date")
    int countNewUsersByDate(@org.springframework.data.repository.query.Param("date") java.time.LocalDate date);

    /**
     * 指定日時点のアクティブユーザー数を取得する（ACTIVE かつ未削除）。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(u) FROM UserEntity u WHERE u.status = 'ACTIVE' AND u.deletedAt IS NULL " +
            "AND u.createdAt <= :endOfDay")
    int countActiveUsersAsOf(@org.springframework.data.repository.query.Param("endOfDay") LocalDateTime endOfDay);

    /**
     * 指定日時点の全ユーザー数（未削除）を取得する。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(u) FROM UserEntity u WHERE u.deletedAt IS NULL " +
            "AND u.createdAt <= :endOfDay")
    int countTotalUsersAsOf(@org.springframework.data.repository.query.Param("endOfDay") LocalDateTime endOfDay);

    /**
     * 指定月に登録されたユーザーのIDリストを取得する（コホート用）。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT u.id FROM UserEntity u WHERE CAST(u.createdAt AS localdate) BETWEEN :monthStart AND :monthEnd")
    java.util.List<Long> findUserIdsCreatedBetween(
            @org.springframework.data.repository.query.Param("monthStart") java.time.LocalDate monthStart,
            @org.springframework.data.repository.query.Param("monthEnd") java.time.LocalDate monthEnd);

    /**
     * 指定ユーザーIDリストのうちアクティブ（ACTIVE かつ未削除）なユーザー数を取得する。
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(u) FROM UserEntity u WHERE u.id IN :userIds " +
            "AND u.status = 'ACTIVE' AND u.deletedAt IS NULL")
    int countActiveByUserIds(@org.springframework.data.repository.query.Param("userIds") java.util.List<Long> userIds);

    /**
     * userId に対応する locale 文字列のみを取得する（UserLocaleFilter 用軽量クエリ）。
     */
    @org.springframework.data.jpa.repository.Query("SELECT u.locale FROM UserEntity u WHERE u.id = :userId AND u.deletedAt IS NULL")
    Optional<String> findLocaleById(@org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * 物理削除対象ユーザーを取得する。
     * @SQLRestriction("deleted_at IS NULL") をバイパスするためネイティブSQLを使用。
     */
    @Query(value = """
        SELECT * FROM users
        WHERE deleted_at < :cutoff
          AND purged_at IS NULL
          AND id != 0
        ORDER BY deleted_at ASC
        """, nativeQuery = true)
    List<UserEntity> findPurgeTargets(
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable);

    /**
     * 退会済み（deleted_atが指定範囲内）かつ未purgeのユーザーを取得する。
     * @SQLRestriction をバイパスするためネイティブSQL使用。
     */
    @Query(value = """
        SELECT * FROM users
        WHERE deleted_at >= :from
          AND deleted_at < :to
          AND purged_at IS NULL
        ORDER BY deleted_at ASC
        """, nativeQuery = true)
    List<UserEntity> findPendingDeletionUsers(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** メンバー一覧表示用の最小プロジェクション */
    interface MemberSummary {
        Long getId();
        String getDisplayName();
        String getAvatarUrl();
    }

    /**
     * メンバー一覧用に displayName・avatarUrl のみを取得する。
     * 暗号化フィールド（lastName/firstName 等）を含むフルエンティティをロードしないことで、
     * seed データの平文カラム値による EncryptionService 復号エラーを回避する。
     */
    @Query(value = "SELECT u.id, u.display_name AS displayName, u.avatar_url AS avatarUrl FROM users u WHERE u.id = :id AND u.deleted_at IS NULL", nativeQuery = true)
    Optional<MemberSummary> findMemberSummaryById(@Param("id") Long id);
}
