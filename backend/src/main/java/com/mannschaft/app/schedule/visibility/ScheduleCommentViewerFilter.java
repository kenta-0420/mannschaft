package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.common.visibility.ScopeUserRoleProjection;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「単一スケジュール × 複数ユーザー」向きの可視性フィルタ（F03.16 §4.5.0 の <b>3 段方式</b>）。
 *
 * <p>メンション通知の宛先絞り込み（§6.3・AC-18 / AC-18b）と
 * {@code GET .../comments/mention-candidates}（§4.4・AC-11b / AC-40）が共有する。</p>
 *
 * <h2>なぜ独立クラスなのか — 「独自の閲覧述語を作らない」方針との関係</h2>
 * <p>§4.5.0 が禁じたのは<b>可視性の判定規則を独自に写像し直すこと</b>である。本クラスは
 * <b>判定規則を一切持たない</b>。最終判定は必ず
 * {@link ContentVisibilityChecker#canView(ReferenceType, Long, Long)} が下し、
 * 途中の足切りも {@link MinViewRoleThreshold#satisfies} という<b>既存の単一正準</b>を
 * そのまま呼ぶだけである。本クラスがやっているのは「呼ぶ順序と回数」の最適化に過ぎない。</p>
 *
 * <h2>3 段方式（設計書 §4.5.0 の表）</h2>
 * <table>
 *   <caption>段と SQL 本数</caption>
 *   <tr><th>段</th><th>処理</th><th>SQL 本数</th></tr>
 *   <tr><td>1</td><td>スコープのロールを候補者全員分まとめて解決</td>
 *       <td><b>候補者数に依らず 2 本</b>（user_roles 1・memberships 1）</td></tr>
 *   <tr><td>2</td><td>{@link MinViewRoleThreshold#satisfies} でメモリ上のロール足切り</td><td>0</td></tr>
 *   <tr><td>3</td><td>残った候補にのみ {@code canView}</td><td>段2 通過後の候補数依存（本 AC 対象外）</td></tr>
 * </table>
 *
 * <p>金型は {@code GoogleCalendarService#filterBackfillSchedules}（同じ思想で向きが逆）。
 * <b>段1 を先に済ませてから段3 を呼ぶ順序を固定する</b> — 足切りを先に通すほど
 * {@code canView} の呼び出し回数が減る。AC-39 は「候補 5 人と 20 人で段1 の SQL 発行数が
 * 同一」を実測して、この構造が崩れていないことを機械的に固定する。</p>
 *
 * <h2>ロール解決を {@code AccessControlService#resolveEffectiveRoleName} に頼らない理由</h2>
 * <p>同メソッドは「1 ユーザー × 1 スコープ」専用で、候補者ごとに呼ぶと候補者数に比例して
 * SQL が増える（AC-39 が禁じる形そのもの）。<b>ロールの決め方（2 系統の UNION ＋ priority 最強）は
 * 同メソッドと完全に同一の規則</b>をここで一括版として実装している。優先度の比較は
 * {@link RolePriority}（{@code roles.priority} と一致させることが同クラスの契約）で
 * メモリ上行うため、優先度取得のための追加 SQL は発行しない。</p>
 */
@Component
@RequiredArgsConstructor
public class ScheduleCommentViewerFilter {

    private final UserRoleRepository userRoleRepository;
    private final MembershipRepository membershipRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;

    /**
     * 予定を閲覧できる候補ユーザーだけに絞り込む（3 段方式）。
     *
     * @param schedule          対象スケジュール（スコープと {@code min_view_role} の供給元）
     * @param candidateUserIds  候補ユーザー ID 集合
     * @return 当該予定を閲覧できるユーザー ID（入力順を保つ）
     */
    public Set<Long> filterViewers(ScheduleEntity schedule, Collection<Long> candidateUserIds) {
        if (schedule == null) {
            return Set.of();
        }
        String scopeType = scopeTypeOf(schedule);
        Long scopeId = scopeIdOf(schedule);
        return filterViewers(
                schedule.getId(), scopeType, scopeId, schedule.getMinViewRole(), candidateUserIds);
    }

    /**
     * 予定を閲覧できる候補ユーザーだけに絞り込む（3 段方式・明示パラメータ版）。
     *
     * @param scheduleId       対象スケジュール ID
     * @param scopeType        {@code "TEAM"} / {@code "ORGANIZATION"}（個人予定は {@code null}）
     * @param scopeId          スコープ ID
     * @param minViewRole      閲覧閾値（{@code null} 可＝閾値なし）
     * @param candidateUserIds 候補ユーザー ID 集合
     * @return 当該予定を閲覧できるユーザー ID（入力順を保つ）
     */
    public Set<Long> filterViewers(
            Long scheduleId,
            String scopeType,
            Long scopeId,
            MinViewRole minViewRole,
            Collection<Long> candidateUserIds) {

        if (scheduleId == null || candidateUserIds == null || candidateUserIds.isEmpty()) {
            return Set.of();
        }
        // null 要素・重複を落として入力順を保つ。IN () を避けるためここで空判定する。
        Set<Long> candidates = new LinkedHashSet<>();
        for (Long id : candidateUserIds) {
            if (id != null) {
                candidates.add(id);
            }
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }

        // ── 段1: スコープのロールを一括解決（候補者数に依らず SQL 2 本）──────────
        Map<Long, String> roleByUser = resolveRoles(scopeType, scopeId, candidates);

        // ── 段2: メモリ上のロール足切り（DB アクセス 0）────────────────────
        // 個人予定（scopeType == null）は §2.2 でコメント機能自体がスコープ外であり、
        // ロール解決結果は空になる。閾値ありならここで全員落ちる（fail-closed 側）。
        Set<Long> survivors = new LinkedHashSet<>();
        for (Long userId : candidates) {
            if (MinViewRoleThreshold.satisfies(roleByUser.get(userId), minViewRole)) {
                survivors.add(userId);
            }
        }
        if (survivors.isEmpty()) {
            return Set.of();
        }

        // ── 段3: 残った候補にのみ canView（F00 ラダーの最終判定）────────────
        // 所属・ORGANIZATION 昇格・CUSTOM_TEMPLATE はここでしか評価できないため、
        // 段2 を通ったからといって省略してはならない（段2 は足切りであって判定ではない）。
        Set<Long> visible = new LinkedHashSet<>();
        for (Long userId : survivors) {
            if (contentVisibilityChecker.canView(ReferenceType.SCHEDULE, scheduleId, userId)) {
                visible.add(userId);
            }
        }
        return visible;
    }

    /**
     * 段1 — 候補ユーザー全員の実効ロール名を <b>SQL 2 本</b>で解決する。
     *
     * <p>規則は {@code AccessControlService#resolveEffectiveRoleName} と同一:
     * 権限ロール（{@code user_roles}）と所属ロール（{@code memberships.role_kind}）の
     * 両系統を集め、{@link RolePriority} が最強（priority 最小）のロール名を採る。</p>
     */
    private Map<Long, String> resolveRoles(String scopeType, Long scopeId, Set<Long> candidates) {
        Map<Long, String> roleByUser = new HashMap<>();
        if (scopeType == null || scopeId == null) {
            // 個人予定・スコープ不明。ロール無し（非メンバー＝最弱）として扱う。
            return roleByUser;
        }

        boolean team = "TEAM".equals(scopeType);

        // SQL 1: 権限ロール（ADMIN / DEPUTY_ADMIN / GUEST 等）。
        List<ScopeUserRoleProjection> permissionRoles = team
                ? userRoleRepository.findScopeRolesByTeamIdAndUserIdIn(scopeId, candidates)
                : userRoleRepository.findScopeRolesByOrganizationIdAndUserIdIn(scopeId, candidates);
        for (ScopeUserRoleProjection row : permissionRoles) {
            mergeStrongest(roleByUser, row.getUserId(), row.getRoleName());
        }

        // SQL 2: 所属ロール（MEMBER / SUPPORTER）。
        ScopeType scope = team ? ScopeType.TEAM : ScopeType.ORGANIZATION;
        List<MembershipRepository.MembershipUserRoleKindProjection> membershipRoles =
                membershipRepository.findActiveRoleKindsByScopeAndUsers(scope, scopeId, candidates);
        for (MembershipRepository.MembershipUserRoleKindProjection row : membershipRoles) {
            if (row.getRoleKind() != null) {
                mergeStrongest(roleByUser, row.getUserId(), row.getRoleKind().name());
            }
        }
        return roleByUser;
    }

    /** priority が最小（＝最強）のロール名を採用する。 */
    private void mergeStrongest(Map<Long, String> roleByUser, Long userId, String roleName) {
        if (userId == null || roleName == null) {
            return;
        }
        String current = roleByUser.get(userId);
        if (current == null || RolePriority.priority(roleName) < RolePriority.priority(current)) {
            roleByUser.put(userId, roleName);
        }
    }

    /** スケジュールのスコープ種別。個人予定は {@code null}（コメント機能のスコープ外・§2.2）。 */
    public static String scopeTypeOf(ScheduleEntity schedule) {
        if (schedule.isTeamScope()) {
            return "TEAM";
        }
        if (schedule.isOrganizationScope()) {
            return "ORGANIZATION";
        }
        return null;
    }

    /** スケジュールのスコープ ID。個人予定は {@code null}。 */
    public static Long scopeIdOf(ScheduleEntity schedule) {
        if (schedule.isTeamScope()) {
            return schedule.getTeamId();
        }
        if (schedule.isOrganizationScope()) {
            return schedule.getOrganizationId();
        }
        return null;
    }
}
