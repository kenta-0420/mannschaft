package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * チーム加盟の再送制限リポジトリ（F01.2.1 §5.1）。
 *
 * <p>{@code AbstractTenantAwareRepository} は継承しない（物理削除・deleted_at 列なし・
 * チーム側から組織を横断して引く読み手があるため。§5.1）。</p>
 */
public interface TeamOrgAffiliationRestrictionRepository
        extends JpaRepository<TeamOrgAffiliationRestrictionEntity, UUID> {

    Optional<TeamOrgAffiliationRestrictionEntity> findByOrganizationIdAndTeamIdAndDirection(
            Long organizationId, Long teamId, TeamOrgAffiliationDirection direction);

    /**
     * 同じ組み合わせの制限行を {@code SELECT ... FOR UPDATE} で取得する（合成規則の読み書きを直列化する。§5.4）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TeamOrgAffiliationRestrictionEntity r "
            + "WHERE r.organizationId = :organizationId AND r.teamId = :teamId AND r.direction = :direction")
    Optional<TeamOrgAffiliationRestrictionEntity> findForUpdate(
            @Param("organizationId") Long organizationId,
            @Param("teamId") Long teamId,
            @Param("direction") TeamOrgAffiliationDirection direction);

    /**
     * 有効な制限の件数（§5.4「判定」: {@code kind='BLOCK' OR restricted_until > :now}）。
     *
     * <p>期限切れの COOLDOWN は判定で無視する（物理削除は夜間バッチ）。{@code :now} は呼び出し側の Clock から渡す
     * （SQL の {@code NOW()} を判定に使わない。§4.6）。</p>
     */
    @Query("SELECT COUNT(r) FROM TeamOrgAffiliationRestrictionEntity r "
            + "WHERE r.organizationId = :organizationId AND r.teamId = :teamId AND r.direction = :direction "
            + "AND (r.kind = :blockKind OR r.restrictedUntil > :now)")
    long countActive(@Param("organizationId") Long organizationId,
                     @Param("teamId") Long teamId,
                     @Param("direction") TeamOrgAffiliationDirection direction,
                     @Param("blockKind") TeamOrgAffiliationRestrictionKind blockKind,
                     @Param("now") Instant now);

    /**
     * 期限付き（COOLDOWN）の制限行が無ければ作る。既にあれば何もしない（UNIQUE 衝突は例外にしない）。
     *
     * <p>{@code INSERT ... ON DUPLICATE KEY UPDATE id = id} なので、並行して2本が走っても1行に収束し、
     * 呼び出し側のトランザクションを rollback-only にしない（AC-G135）。既存行との合成（BLOCK を上書きしない・
     * 期限の遅いほうを残す）は、この後に行ロックを取って Java 側の合成規則で行う。</p>
     *
     * <p>{@code restricted_until} は UTC 壁時計の DATETIME。Java の日時型をプレースホルダへ束縛すると
     * JDBC のタイムゾーン変換に依存するため、エポック秒を {@code TIMESTAMPADD} で UTC 壁時計へ戻す
     * （セッションのタイムゾーン設定に依らない）。{@code created_at} / {@code updated_at} は
     * {@code UTC_TIMESTAMP()}。</p>
     */
    @Modifying
    @Query(value = """
            INSERT INTO team_org_affiliation_restrictions
                (id, organization_id, team_id, direction, kind, reason, restricted_until, created_by,
                 created_at, updated_at)
            VALUES
                (:id, :organizationId, :teamId, :direction, 'COOLDOWN', :reason,
                 TIMESTAMPADD(SECOND, :untilEpochSecond, '1970-01-01 00:00:00'), :createdBy,
                 UTC_TIMESTAMP(), UTC_TIMESTAMP())
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertCooldownIfAbsent(@Param("id") UUID id,
                               @Param("organizationId") Long organizationId,
                               @Param("teamId") Long teamId,
                               @Param("direction") String direction,
                               @Param("reason") String reason,
                               @Param("untilEpochSecond") long untilEpochSecond,
                               @Param("createdBy") Long createdBy);

    /**
     * 無期限（BLOCK）の制限行が無ければ作る。既にあれば何もしない。意味は {@link #insertCooldownIfAbsent}。
     */
    @Modifying
    @Query(value = """
            INSERT INTO team_org_affiliation_restrictions
                (id, organization_id, team_id, direction, kind, reason, restricted_until, created_by,
                 created_at, updated_at)
            VALUES
                (:id, :organizationId, :teamId, :direction, 'BLOCK', :reason, NULL, :createdBy,
                 UTC_TIMESTAMP(), UTC_TIMESTAMP())
            ON DUPLICATE KEY UPDATE id = id
            """, nativeQuery = true)
    int insertBlockIfAbsent(@Param("id") UUID id,
                            @Param("organizationId") Long organizationId,
                            @Param("teamId") Long teamId,
                            @Param("direction") String direction,
                            @Param("reason") String reason,
                            @Param("createdBy") Long createdBy);

    // ========================================================================
    // F01.2.1 2-C: 止めた側の制限一覧と解除（§5.4「解除一覧に出す行」）
    //
    // 一覧・解除の対象は「止めた側が意思を持って作った行」だけに絞る。組織側は (TEAM_APPLY, REJECTED)、
    // チーム側は (ORG_INVITE, DECLINED)。自分の操作で自分側が止まる WITHDRAWN・CANCELLED と、
    // 相手側が作った行は、一覧に出さず解除もさせない（制限の迂回を封じる）。
    // ========================================================================

    /**
     * 組織が止めている、いま有効な制限を作成日時の降順で引く（{@code kind='BLOCK' OR restricted_until > :now}）。
     */
    @Query(value = "SELECT r FROM TeamOrgAffiliationRestrictionEntity r "
            + "WHERE r.organizationId = :organizationId AND r.direction = :direction AND r.reason = :reason "
            + "AND (r.kind = :blockKind OR r.restrictedUntil > :now) "
            + "ORDER BY r.createdAt DESC, r.id DESC",
            countQuery = "SELECT COUNT(r) FROM TeamOrgAffiliationRestrictionEntity r "
            + "WHERE r.organizationId = :organizationId AND r.direction = :direction AND r.reason = :reason "
            + "AND (r.kind = :blockKind OR r.restrictedUntil > :now)")
    org.springframework.data.domain.Page<TeamOrgAffiliationRestrictionEntity> findActivePageByOrganization(
            @Param("organizationId") Long organizationId,
            @Param("direction") TeamOrgAffiliationDirection direction,
            @Param("reason") com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason reason,
            @Param("blockKind") TeamOrgAffiliationRestrictionKind blockKind,
            @Param("now") Instant now,
            org.springframework.data.domain.Pageable pageable);

    /**
     * チームが止めている、いま有効な制限を作成日時の降順で引く（{@code kind='BLOCK' OR restricted_until > :now}）。
     */
    @Query(value = "SELECT r FROM TeamOrgAffiliationRestrictionEntity r "
            + "WHERE r.teamId = :teamId AND r.direction = :direction AND r.reason = :reason "
            + "AND (r.kind = :blockKind OR r.restrictedUntil > :now) "
            + "ORDER BY r.createdAt DESC, r.id DESC",
            countQuery = "SELECT COUNT(r) FROM TeamOrgAffiliationRestrictionEntity r "
            + "WHERE r.teamId = :teamId AND r.direction = :direction AND r.reason = :reason "
            + "AND (r.kind = :blockKind OR r.restrictedUntil > :now)")
    org.springframework.data.domain.Page<TeamOrgAffiliationRestrictionEntity> findActivePageByTeam(
            @Param("teamId") Long teamId,
            @Param("direction") TeamOrgAffiliationDirection direction,
            @Param("reason") com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason reason,
            @Param("blockKind") TeamOrgAffiliationRestrictionKind blockKind,
            @Param("now") Instant now,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 組織が止めた制限を解除する。ID・組織・向き・理由がすべて一致する行だけを消す
     * （他組織の ID・相手側が作った行・WITHDRAWN の行は 0 件になる）。
     *
     * @return 削除した行数（0 または 1）
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM TeamOrgAffiliationRestrictionEntity r WHERE r.id = :id "
            + "AND r.organizationId = :organizationId AND r.direction = :direction AND r.reason = :reason")
    int deleteOwnedByOrganization(@Param("id") UUID id,
                                  @Param("organizationId") Long organizationId,
                                  @Param("direction") TeamOrgAffiliationDirection direction,
                                  @Param("reason") com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason reason);

    /**
     * チームが止めた制限を解除する。ID・チーム・向き・理由がすべて一致する行だけを消す
     * （他チームの ID・相手側が作った行・CANCELLED の行は 0 件になる）。
     *
     * @return 削除した行数（0 または 1）
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM TeamOrgAffiliationRestrictionEntity r WHERE r.id = :id "
            + "AND r.teamId = :teamId AND r.direction = :direction AND r.reason = :reason")
    int deleteOwnedByTeam(@Param("id") UUID id,
                          @Param("teamId") Long teamId,
                          @Param("direction") TeamOrgAffiliationDirection direction,
                          @Param("reason") com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason reason);
}
