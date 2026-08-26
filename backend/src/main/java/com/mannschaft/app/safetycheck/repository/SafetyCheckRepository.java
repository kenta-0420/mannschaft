package com.mannschaft.app.safetycheck.repository;

import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.SafetyCheckStatus;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 安否確認リポジトリ。
 */
public interface SafetyCheckRepository extends JpaRepository<SafetyCheckEntity, Long> {

    /**
     * スコープ別の安否確認一覧を作成日時降順で取得する。
     */
    Page<SafetyCheckEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            SafetyCheckScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ別かつステータス指定で安否確認一覧を取得する。
     */
    Page<SafetyCheckEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            SafetyCheckScopeType scopeType, Long scopeId, SafetyCheckStatus status, Pageable pageable);

    /**
     * アクティブな安否確認一覧を取得する（リマインド処理用）。
     */
    List<SafetyCheckEntity> findByStatus(SafetyCheckStatus status);

    /**
     * スコープ別の安否確認履歴を取得する（クローズ済み）。
     */
    @Query("SELECT sc FROM SafetyCheckEntity sc WHERE sc.scopeType = :scopeType AND sc.scopeId = :scopeId "
            + "AND sc.status = 'CLOSED' ORDER BY sc.closedAt DESC")
    Page<SafetyCheckEntity> findClosedByScopeOrderByClosedAtDesc(
            @Param("scopeType") SafetyCheckScopeType scopeType,
            @Param("scopeId") Long scopeId,
            Pageable pageable);

    /**
     * 横断検索（グローバル検索）用のキーワード検索。閲覧者の可視スコープに限定する。
     *
     * <p>安否確認の {@code message}（本文）は災害時の機微情報を含むため、
     * 閲覧者が所属するチーム／組織のものに限定する。</p>
     *
     * <p>{@code GROUP} スコープは {@code scopeId} がチーム／組織 ID ではなくグループ ID を指し、
     * クエリ段階で所属解決ができないため本検索の対象から除外する（fail-closed）。
     * グループ安否確認は安否確認ドメインの一覧 API から参照する。</p>
     *
     * <p>呼び出し側は {@code teamIds} / {@code orgIds} が空の場合、{@code IN ()} の発行を避けるため
     * ダミー値（{@code -1L}）で埋めること。</p>
     *
     * @param keyword  検索キーワード
     * @param teamIds  閲覧者が所属するチーム ID 集合（非空・空ならダミー値）
     * @param orgIds   閲覧者が所属する組織 ID 集合（非空・空ならダミー値）
     * @param pageable 取得件数
     * @return 可視スコープ内の検索結果
     */
    @Query("""
            SELECT sc FROM SafetyCheckEntity sc
            WHERE (sc.title LIKE %:keyword% OR sc.message LIKE %:keyword%)
              AND ((sc.scopeType = com.mannschaft.app.safetycheck.SafetyCheckScopeType.TEAM
                    AND sc.scopeId IN :teamIds)
                OR (sc.scopeType = com.mannschaft.app.safetycheck.SafetyCheckScopeType.ORGANIZATION
                    AND sc.scopeId IN :orgIds))
            """)
    java.util.List<SafetyCheckEntity> searchByKeyword(@Param("keyword") String keyword,
                                                      @Param("teamIds") java.util.Collection<Long> teamIds,
                                                      @Param("orgIds") java.util.Collection<Long> orgIds,
                                                      Pageable pageable);
}
