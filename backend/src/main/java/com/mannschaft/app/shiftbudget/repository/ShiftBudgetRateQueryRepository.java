package com.mannschaft.app.shiftbudget.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * F08.7 シフト予算逆算 API 専用の時給集計リポジトリ。
 *
 * <p>F03.5 の {@code ShiftHourlyRateRepository} とは責務分離している（設計書 §3 / 家老案）。
 * F03.5 は CRUD・履歴取得を、本リポジトリは「予算計算用の集計（チーム平均・ポジション平均）」を担当する。</p>
 *
 * <p><strong>スキーマ翻訳の注意 (Phase 9-α 暫定):</strong></p>
 * <ul>
 *   <li>設計書 §4.1 v1.2 確定 SQL は {@code team_members} テーブル + {@code position_id} 参照を前提としているが、
 *       本リポジトリの実 DB スキーマには両者が未導入。Phase 9-β で {@code team_members} を新設し
 *       Phase 9-γ で {@code position_id} を追加する計画。</li>
 *   <li>Phase 9-α 暫定実装として、以下で「アクティブメンバー」を等価判定する:
 *       <ul>
 *         <li><b>チーム所属</b>: {@code user_roles.team_id = :teamId} (設計書の team_members.team_id 相当)</li>
 *         <li><b>退職判定</b>: {@code users.deleted_at IS NULL AND users.status = 'ACTIVE'}
 *             (設計書の team_members.left_at IS NULL 相当)</li>
 *         <li><b>時給有効性</b>: 適用開始日が今日以前の最新時給を採用 (shift_hourly_rates.deleted_at は未導入のため省略)</li>
 *       </ul>
 *   </li>
 *   <li>POSITION_AVG モードは、ポジション別メンバー区分が現スキーマで表現できないため、
 *       Phase 9-α 時点では「指定 position_id の {@code shift_positions} 行が存在することのみ検証」し、
 *       時給平均はチーム全体の MEMBER_AVG にフォールバックする (TODO Phase 9-γ 真の実装)。</li>
 * </ul>
 */
@Repository
public interface ShiftBudgetRateQueryRepository
        extends JpaRepository<com.mannschaft.app.shift.entity.ShiftHourlyRateEntity, Long> {

    /**
     * 指定チームの「アクティブメンバー」現行平均時給を返す。
     *
     * <p>計算ロジック:</p>
     * <ol>
     *   <li>チーム所属ユーザー (user_roles.team_id) のうち、削除/非アクティブを除外</li>
     *   <li>各ユーザーの最新有効時給 (effective_from <= :today で最大の effective_from) を抽出</li>
     *   <li>その時給の AVG と COUNT を返す</li>
     * </ol>
     *
     * @param teamId チームID
     * @param today  基準日 (通常 {@link java.time.LocalDate#now()})
     * @return 平均時給と対象人数。対象 0 人時は avgRate=null / memberCount=0 を保証
     */
    @Query(value =
            "SELECT COALESCE(AVG(rate.hourly_rate), NULL) AS avg_rate, " +
            "       COUNT(rate.user_id)                  AS member_count " +
            "FROM ( " +
            "    SELECT h1.user_id, h1.hourly_rate " +
            "    FROM shift_hourly_rates h1 " +
            "    INNER JOIN ( " +
            "        SELECT user_id, MAX(effective_from) AS max_from " +
            "        FROM shift_hourly_rates " +
            "        WHERE team_id = :teamId AND effective_from <= :today " +
            "        GROUP BY user_id " +
            "    ) latest ON latest.user_id = h1.user_id AND latest.max_from = h1.effective_from " +
            "    WHERE h1.team_id = :teamId " +
            ") rate " +
            "INNER JOIN user_roles ur ON ur.user_id = rate.user_id AND ur.team_id = :teamId " +
            "INNER JOIN users u ON u.id = rate.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Object[]> findTeamAverageRate(@Param("teamId") Long teamId, @Param("today") LocalDate today);

    /**
     * 指定 position_id が指定チームに紐付いて存在するかを返す。
     *
     * <p>POSITION_AVG モードのバリデーション用。</p>
     */
    @Query(value =
            "SELECT COUNT(*) FROM shift_positions sp " +
            "WHERE sp.id = :positionId AND sp.team_id = :teamId",
            nativeQuery = true)
    long countPositionInTeam(@Param("positionId") Long positionId, @Param("teamId") Long teamId);

    /**
     * 指定チームが存在し、指定組織に所属していることを返す。
     *
     * <p>多テナント分離: {@code team_org_memberships} ACTIVE 行で組織所属を厳密検証する。
     * 設計書 §9.5 「team_id の組織所属を必ず検証」に対応。</p>
     */
    @Query(value =
            "SELECT COUNT(*) FROM teams t " +
            "INNER JOIN team_org_memberships tom ON tom.team_id = t.id " +
            "WHERE t.id = :teamId " +
            "  AND t.deleted_at IS NULL " +
            "  AND tom.organization_id = :organizationId " +
            "  AND tom.status = 'ACTIVE'",
            nativeQuery = true)
    long countTeamInOrganization(@Param("teamId") Long teamId,
                                 @Param("organizationId") Long organizationId);

    /**
     * 指定チームの組織IDを返す（ACTIVE な team_org_memberships から）。
     *
     * <p>権限チェック前のテナント解決に利用する。</p>
     */
    @Query(value =
            "SELECT tom.organization_id FROM team_org_memberships tom " +
            "INNER JOIN teams t ON t.id = tom.team_id " +
            "WHERE tom.team_id = :teamId " +
            "  AND tom.status = 'ACTIVE' " +
            "  AND t.deleted_at IS NULL " +
            "LIMIT 1",
            nativeQuery = true)
    Optional<Long> findOrganizationIdByTeamId(@Param("teamId") Long teamId);

    /**
     * 指定チームの平均時給（POSITION_AVG フォールバック用）。
     *
     * <p>Phase 9-α 暫定: 真のポジション別集計が未実装のため、
     * 同一の MEMBER_AVG クエリを別名でラップして提供する。</p>
     *
     * @see #findTeamAverageRate(Long, LocalDate)
     */
    default BigDecimal averageRateForPositionFallback(Long teamId, LocalDate today) {
        List<Object[]> rows = findTeamAverageRate(teamId, today);
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return null;
        }
        return new BigDecimal(rows.get(0)[0].toString());
    }
}
