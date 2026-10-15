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

    /**
     * 横断検索（グローバル検索）用のキーワード検索。閲覧者の可視スコープに限定する。
     *
     * <p>チケットはスコープ列を持たないため、カテゴリ（{@code QueueCategoryEntity}）のスコープに委ねる。
     * 可視範囲は「自分が発券したチケット」「所属チーム／組織が運営するカテゴリのチケット」の和集合とする。
     * {@code guestName}（来場者氏名）は個人情報であり、運営スコープ外には出さない。</p>
     *
     * <p>呼び出し側は {@code teamIds} / {@code orgIds} が空の場合、{@code IN ()} の発行を避けるため
     * ダミー値（{@code -1L}）で埋めること。</p>
     *
     * @param keyword  検索キーワード
     * @param teamIds  閲覧者が所属するチーム ID 集合（非空・空ならダミー値）
     * @param orgIds   閲覧者が所属する組織 ID 集合（非空・空ならダミー値）
     * @param userId   閲覧者ユーザー ID（発券者一致判定用）
     * @param pageable 取得件数
     * @return 可視スコープ内の検索結果
     */
    @Query("""
            SELECT t FROM QueueTicketEntity t
            WHERE (t.ticketNumber LIKE %:keyword% OR t.guestName LIKE %:keyword%)
              AND (t.userId = :userId
                OR t.categoryId IN (
                    SELECT c.id FROM QueueCategoryEntity c
                    WHERE c.deletedAt IS NULL
                      AND ((c.scopeType = com.mannschaft.app.queue.QueueScopeType.TEAM
                            AND c.scopeId IN :teamIds)
                        OR (c.scopeType = com.mannschaft.app.queue.QueueScopeType.ORGANIZATION
                            AND c.scopeId IN :orgIds))
                ))
            """)
    List<QueueTicketEntity> searchByKeyword(@Param("keyword") String keyword,
                                            @Param("teamIds") java.util.Collection<Long> teamIds,
                                            @Param("orgIds") java.util.Collection<Long> orgIds,
                                            @Param("userId") Long userId,
                                            org.springframework.data.domain.Pageable pageable);
}
