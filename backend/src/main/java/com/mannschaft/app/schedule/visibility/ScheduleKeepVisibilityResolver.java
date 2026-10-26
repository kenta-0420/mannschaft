package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.mapping.ScheduleKeepStatusMapper;
import com.mannschaft.app.common.visibility.mapping.ScheduleKeepVisibilityMapper;
import com.mannschaft.app.schedule.ScheduleKeepScopeType;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * F00 {@code ReferenceType.SCHEDULE_KEEP} の可視性判定 Resolver（F03.17 §4.6.2）。
 *
 * <p><b>独自の可視性述語を書かず F00 正準の語彙へ寄せる</b>。判定は
 * {@link ScheduleKeepVisibilityMapper} が返す {@link StandardVisibility} と、
 * {@link MembershipBatchQueryService} が返す {@link UserScopeRoleSnapshot} の
 * 標準 API（{@link UserScopeRoleSnapshot#hasRoleOrAbove}）だけで完結する。</p>
 *
 * <p><b>応援者（SUPPORTER）・ゲストの遮断</b>: キープは可視性設定列を持たず、
 * チーム／組織スコープでは常に {@link StandardVisibility#MEMBERS_AND_ABOVE} で評価される。
 * すなわち {@code hasRoleOrAbove(scope, "MEMBER")} を満たさない SUPPORTER / GUEST /
 * 非メンバー / 未認証は<b>すべて不可視</b>になり、呼び出し側は 404 で存在ごと秘匿する。
 * {@code SCOPE_AFFILIATED}（応援者を含む直接所属軸）を使わないのがこの機能の要点である
 * （{@code EventVisibilityMapper} の締め直しと同じ判断）。</p>
 *
 * <p>{@code schedule_keeps} は UUIDv7 主キーのため <b>UUID 経路</b>（{@link #canViewUuid} /
 * {@link #filterAccessibleUuid}）を実装し、Long 経路はデフォルトのまま fail-closed とする
 * （{@code MatchVisibilityResolver} 手本・{@code ReferenceType#idKind()==UUID_V7}）。
 * 基底の {@code AbstractContentVisibilityResolver} は {@code Long} 主キー専用
 * （{@code loadProjections(Collection<Long>)}）のため継承できない。</p>
 *
 * <p>論理削除済みのキープは {@code @SQLRestriction("deleted_at IS NULL")} により射影に現れず、
 * 不在＝deny となる（存在を漏らさない）。</p>
 *
 * <p>設計: {@code docs/features/F03.17_schedule_keep.md} §4.6.2 / §4.6.4</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleKeepVisibilityResolver implements ContentVisibilityResolver<Enum<?>> {

    /** {@link StandardVisibility#MEMBERS_AND_ABOVE} が要求するロール閾値。 */
    private static final String REQUIRED_ROLE_MEMBER = "MEMBER";

    private final ScheduleKeepRepository scheduleKeepRepository;
    private final MembershipBatchQueryService membershipBatchQueryService;

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.SCHEDULE_KEEP;
    }

    // ─── Long 経路（未使用・fail-closed） ─────────────────────────

    @Override
    public boolean canView(Long contentId, Long viewerUserId) {
        log.warn("SCHEDULE_KEEP resolver called via Long path (should be UUID): contentId={}", contentId);
        return false;
    }

    @Override
    public Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId) {
        return Collections.emptySet();
    }

    // ─── UUID 経路（本流） ───────────────────────────────────────

    @Override
    public boolean canViewUuid(UUID contentId, Long viewerUserId) {
        if (contentId == null || viewerUserId == null) {
            // 未認証（GUEST 扱い）は MEMBERS_AND_ABOVE / PRIVATE のいずれでも不可視。
            return false;
        }
        return filterAccessibleUuid(List.of(contentId), viewerUserId).contains(contentId);
    }

    @Override
    public Set<UUID> filterAccessibleUuid(Collection<UUID> contentIds, Long viewerUserId) {
        if (contentIds == null || contentIds.isEmpty() || viewerUserId == null) {
            return Collections.emptySet();
        }

        // SQL 1: 実存確認込みの射影取得（論理削除済みは @SQLRestriction で除外される）。
        List<ScheduleKeepVisibilityProjection> rows =
                scheduleKeepRepository.findVisibilityProjectionsByIdIn(new HashSet<>(contentIds));
        if (rows == null || rows.isEmpty()) {
            return Collections.emptySet();
        }

        // SQL 2〜: メンバーシップ・スナップショットを 1 回だけ構築（N+1 回避）。
        Set<ScopeKey> directScopes = collectDirectScopes(rows);
        UserScopeRoleSnapshot snapshot =
                membershipBatchQueryService.snapshotForUser(viewerUserId, directScopes, Set.of());

        Set<UUID> accessible = new HashSet<>();
        for (ScheduleKeepVisibilityProjection row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            if (isVisible(row, viewerUserId, snapshot)) {
                accessible.add(row.getId());
            }
        }
        return accessible;
    }

    /**
     * メンバーシップ照会が必要なスコープ（TEAM / ORGANIZATION）を集約する。
     * 個人スコープは所有者判定のみで足りるため対象外。
     */
    private Set<ScopeKey> collectDirectScopes(List<ScheduleKeepVisibilityProjection> rows) {
        Set<ScopeKey> scopes = new HashSet<>();
        for (ScheduleKeepVisibilityProjection row : rows) {
            ScopeKey scope = scopeOf(row);
            if (scope != null) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    /**
     * TEAM / ORGANIZATION スコープの {@link ScopeKey} を返す。
     * 個人スコープ・スコープ判定不能な行は {@code null}（メンバーシップ判定の対象外）。
     */
    private ScopeKey scopeOf(ScheduleKeepVisibilityProjection row) {
        ScheduleKeepScopeType type = row.scopeType();
        Long scopeId = row.scopeId();
        if (type == null || type == ScheduleKeepScopeType.PERSONAL || scopeId == null) {
            return null;
        }
        return new ScopeKey(type.membershipScopeType(), scopeId);
    }

    /**
     * status 軸 × visibility 軸の AND で可視性を判定する（F00 §7.5 と同じ合成）。
     */
    private boolean isVisible(
            ScheduleKeepVisibilityProjection row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {

        // status 軸。キープは 3 状態とも PUBLISHED に写像される（理由は ScheduleKeepStatusMapper）。
        if (row.getStatus() == null) {
            log.warn("schedule_keeps.status が null の行を検出（fail-closed）: id={}", row.getId());
            return false;
        }
        if (ScheduleKeepStatusMapper.toStandard(row.getStatus()) != ContentStatus.PUBLISHED) {
            return false;
        }

        // SystemAdmin 高速パス（F00 §15 D-13。実存確認後に短絡する）。
        if (snapshot.isSystemAdmin()) {
            return true;
        }

        ScheduleKeepScopeType scopeType = row.scopeType();
        if (scopeType == null) {
            // XOR 制約が破れた行。判定材料が無いので fail-closed。
            log.warn("スコープ列の排他条件を満たさない schedule_keeps 行を検出（fail-closed）: id={}",
                    row.getId());
            return false;
        }

        // visibility 軸。F00 の StandardVisibility 語彙のみで評価する。
        StandardVisibility level = ScheduleKeepVisibilityMapper.toStandard(scopeType);
        return switch (level) {
            // チーム／組織スコープ: MEMBER 以上のみ。SUPPORTER / GUEST / 非メンバーはここで落ちる。
            case MEMBERS_AND_ABOVE -> {
                ScopeKey scope = scopeOf(row);
                yield scope != null && snapshot.hasRoleOrAbove(scope, REQUIRED_ROLE_MEMBER);
            }
            // 個人スコープ: 所有者本人のみ。
            case PRIVATE -> Objects.equals(viewerUserId, row.getUserId());
            // ScheduleKeepVisibilityMapper は上記 2 値しか返さない。
            // 将来値が増えたときに黙って通さないよう fail-closed で塞ぐ。
            default -> {
                log.warn("想定外の StandardVisibility を検出（fail-closed）: level={} id={}",
                        level, row.getId());
                yield false;
            }
        };
    }
}
