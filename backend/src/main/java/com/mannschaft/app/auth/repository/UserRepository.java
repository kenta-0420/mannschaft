package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ユーザーリポジトリ。
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    List<UserEntity> findByStatusAndCreatedAtBefore(UserStatus status, LocalDateTime threshold);

    boolean existsByEmail(String email);

    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE u.displayName LIKE %:keyword% OR u.email LIKE %:keyword%")
    java.util.List<UserEntity> searchByKeyword(@org.springframework.data.repository.query.Param("keyword") String keyword, org.springframework.data.domain.Pageable pageable);

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
}
