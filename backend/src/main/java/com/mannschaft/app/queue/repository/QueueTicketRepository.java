package com.mannschaft.app.queue.repository;

import com.mannschaft.app.queue.TicketStatus;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 順番待ちチケットリポジトリ。
 */
public interface QueueTicketRepository extends JpaRepository<QueueTicketEntity, Long> {

    /**
     * カウンターIDと発行日でWAITINGチケットをポジション順に取得する。
     */
    List<QueueTicketEntity> findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
            Long counterId, LocalDate issuedDate, TicketStatus status);

    /**
     * カウンターIDと発行日で全チケットをポジション順に取得する。
     */
    List<QueueTicketEntity> findByCounterIdAndIssuedDateOrderByPositionAsc(
            Long counterId, LocalDate issuedDate);

    /**
     * カテゴリIDと発行日でWAITINGチケットをポジション順に取得する。
     */
    List<QueueTicketEntity> findByCategoryIdAndIssuedDateAndStatusOrderByPositionAsc(
            Long categoryId, LocalDate issuedDate, TicketStatus status);

    /**
     * ユーザーIDと発行日でチケットを取得する。
     */
    List<QueueTicketEntity> findByUserIdAndIssuedDateOrderByCreatedAtDesc(
            Long userId, LocalDate issuedDate);

    /**
     * ユーザーのアクティブチケット数を取得する。
     */
    @Query("SELECT COUNT(t) FROM QueueTicketEntity t WHERE t.userId = :userId "
            + "AND t.issuedDate = :issuedDate AND t.status IN ('WAITING', 'CALLED')")
    long countActiveTicketsByUserIdAndIssuedDate(
            @Param("userId") Long userId, @Param("issuedDate") LocalDate issuedDate);

    /**
     * カウンターの当日の待ちチケット数を取得する。
     */
    long countByCounterIdAndIssuedDateAndStatus(
            Long counterId, LocalDate issuedDate, TicketStatus status);

    /**
     * カウンターの当日の最大ポジションを取得する。
     */
    @Query("SELECT COALESCE(MAX(t.position), 0) FROM QueueTicketEntity t "
            + "WHERE t.counterId = :counterId AND t.issuedDate = :issuedDate")
    int findMaxPositionByCounterIdAndIssuedDate(
            @Param("counterId") Long counterId, @Param("issuedDate") LocalDate issuedDate);

    /**
     * カウンターの当日の最大チケット番号を取得する。
     */
    @Query("SELECT COUNT(t) FROM QueueTicketEntity t "
            + "WHERE t.counterId = :counterId AND t.issuedDate = :issuedDate")
    long countByCounterIdAndIssuedDate(
            @Param("counterId") Long counterId, @Param("issuedDate") LocalDate issuedDate);

    /**
     * カテゴリIDと発行日で全チケットを取得する。
     */
    List<QueueTicketEntity> findByCategoryIdAndIssuedDateOrderByPositionAsc(
            Long categoryId, LocalDate issuedDate);

    /**
     * 呼び出し済みで一定時間経過したチケットを取得する（不在判定用）。
     */
    @Query("SELECT t FROM QueueTicketEntity t WHERE t.status = 'CALLED' "
            + "AND t.issuedDate = :issuedDate")
    List<QueueTicketEntity> findCalledTicketsByIssuedDate(
            @Param("issuedDate") LocalDate issuedDate);

    /**
     * IDとカウンターIDでチケットを取得する。
     */
    Optional<QueueTicketEntity> findByIdAndCounterId(Long id, Long counterId);
}
