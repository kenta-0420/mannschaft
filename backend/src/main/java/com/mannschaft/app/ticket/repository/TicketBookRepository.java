package com.mannschaft.app.ticket.repository;

import com.mannschaft.app.ticket.TicketBookStatus;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 発行済み回数券リポジトリ。
 */
public interface TicketBookRepository extends JpaRepository<TicketBookEntity, Long> {

    /**
     * ユーザーのチケット一覧をステータスで絞り込む。
     */
    List<TicketBookEntity> findByUserIdAndTeamIdAndStatus(Long userId, Long teamId, TicketBookStatus status);

    /**
     * ユーザーのチケット一覧を全件取得する。
     */
    List<TicketBookEntity> findByUserIdAndTeamIdOrderByCreatedAtDesc(Long userId, Long teamId);

    /**
     * チームのチケット発行一覧をページング取得する。
     */
    Page<TicketBookEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId, Pageable pageable);

    /**
     * チームのチケット発行一覧をステータスで絞り込む。
     */
    Page<TicketBookEntity> findByTeamIdAndStatusOrderByCreatedAtDesc(Long teamId, TicketBookStatus status, Pageable pageable);

    /**
     * チームとIDでチケットを取得する。
     */
    Optional<TicketBookEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * 排他ロック付きでチケットを取得する（消化時の二重カウント防止）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM TicketBookEntity b WHERE b.id = :id AND b.teamId = :teamId")
    Optional<TicketBookEntity> findByIdAndTeamIdForUpdate(@Param("id") Long id, @Param("teamId") Long teamId);

    /**
     * 期限切れバッチ用: ACTIVE かつ期限超過のチケットを検索する。
     */
    @Query("SELECT b FROM TicketBookEntity b WHERE b.status = 'ACTIVE' AND b.expiresAt IS NOT NULL AND b.expiresAt < :now")
    List<TicketBookEntity> findExpiredActiveBooks(@Param("now") LocalDateTime now);

    /**
     * PENDING クリーンアップバッチ用。
     */
    @Query("SELECT b FROM TicketBookEntity b WHERE b.status = 'PENDING' AND b.createdAt < :cutoff")
    List<TicketBookEntity> findStalePendingBooks(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 同一ユーザー × 同一商品で PENDING のチケットを検索する（重複 Checkout 防止）。
     */
    Optional<TicketBookEntity> findByUserIdAndProductIdAndStatus(Long userId, Long productId, TicketBookStatus status);

    /**
     * ユーザーの ACTIVE チケット数を取得する（退会/脱退ブロック用）。
     */
    long countByUserIdAndStatus(Long userId, TicketBookStatus status);

    /**
     * チームのステータス別チケット数を取得する（統計用）。
     */
    long countByTeamIdAndStatus(Long teamId, TicketBookStatus status);

    /**
     * 顧客のチケット横断サマリ: 特定ユーザーの ACTIVE なチケットを取得する。
     */
    List<TicketBookEntity> findByUserIdAndTeamIdAndStatusOrderByExpiresAtAsc(Long userId, Long teamId, TicketBookStatus status);

    /**
     * 期限切れ事前通知用: 指定日数後に期限を迎えるチケットを検索する。
     */
    @Query("SELECT b FROM TicketBookEntity b WHERE b.status = 'ACTIVE' AND b.expiresAt IS NOT NULL " +
           "AND FUNCTION('DATEDIFF', b.expiresAt, :now) = :days")
    List<TicketBookEntity> findBooksExpiringInDays(@Param("now") LocalDateTime now, @Param("days") int days);
}
