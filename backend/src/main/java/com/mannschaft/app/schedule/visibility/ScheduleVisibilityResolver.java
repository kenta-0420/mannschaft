package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.ScheduleVisibilityMapper;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase B — {@link ReferenceType#SCHEDULE} 用の可視性 Resolver。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §5.1 / §5.2 / §7.5 / §11.6 / §15 D-13/D-14/D-16。</p>
 *
 * <p><strong>機能側 visibility との対応</strong>（§5.2 + Resolver 内派生 PERSONAL_PRIVATE）:</p>
 * <ul>
 *   <li>{@link ScheduleVisibility#MEMBERS_ONLY} → {@link StandardVisibility#SCOPE_AFFILIATED}
 *       （W5 正準化・挙動保存。応援者包含/除外は別軸の {@code min_view_role} が司る）</li>
 *   <li>{@link ScheduleVisibility#ORGANIZATION} → {@link StandardVisibility#ORGANIZATION_WIDE}</li>
 *   <li>{@link ScheduleVisibility#CUSTOM_TEMPLATE} → {@link StandardVisibility#CUSTOM_TEMPLATE}</li>
 *   <li>PERSONAL スコープ（{@code team_id / organization_id} が null） →
 *       Projection が {@link ScheduleEffectiveVisibility#PERSONAL_PRIVATE} を返し、
 *       {@link StandardVisibility#PRIVATE} に正規化される（§15 D-3 の制約に従い既存
 *       {@link ScheduleVisibility} に PRIVATE 値を増やさず、Resolver 内派生 enum で表現）。</li>
 * </ul>
 *
 * <p><strong>status × visibility 合成</strong>（§7.5）:</p>
 * <ul>
 *   <li>{@link ScheduleStatus#SCHEDULED} / {@link ScheduleStatus#COMPLETED} →
 *       {@link ContentStatus#PUBLISHED}（visibility 評価へ進む）</li>
 *   <li>{@link ScheduleStatus#CANCELLED} → {@link ContentStatus#PUBLISHED}（メンバーには
 *       キャンセル済みも表示する運用。アーカイブ扱いにはしない）</li>
 *   <li>{@link ScheduleStatus} が null → fail-closed（{@link ContentStatus#DELETED}）</li>
 * </ul>
 *
 * <p><strong>制約</strong>:</p>
 * <ul>
 *   <li>本 Resolver は {@code AccessControlService} の 12 メソッドに一切触れない（§15 D-14）。</li>
 *   <li>他 Resolver を inject せず、必要であれば
 *       {@link com.mannschaft.app.common.visibility.ContentVisibilityChecker} を通じて参照する（§15 D-16）。</li>
 * </ul>
 */
@Component
public class ScheduleVisibilityResolver
        extends AbstractContentVisibilityResolver<ScheduleEffectiveVisibility, ScheduleVisibilityProjection> {

    private final ScheduleRepository scheduleRepository;

    public ScheduleVisibilityResolver(
            ScheduleRepository scheduleRepository,
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.scheduleRepository = scheduleRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.SCHEDULE;
    }

    @Override
    protected List<ScheduleVisibilityProjection> loadProjections(Collection<Long> ids) {
        return scheduleRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(ScheduleEffectiveVisibility visibility) {
        return switch (visibility) {
            case MEMBERS_ONLY -> ScheduleVisibilityMapper.toStandard(ScheduleVisibility.MEMBERS_ONLY);
            case ORGANIZATION -> ScheduleVisibilityMapper.toStandard(ScheduleVisibility.ORGANIZATION);
            case CUSTOM_TEMPLATE -> ScheduleVisibilityMapper.toStandard(ScheduleVisibility.CUSTOM_TEMPLATE);
            case PERSONAL_PRIVATE -> StandardVisibility.PRIVATE;
        };
    }

    /**
     * フェーズ M2: 組織全体公開（{@link ScheduleVisibility#ORGANIZATION}）のスケジュールが
     * <strong>ORGANIZATION スコープ</strong>のコンテンツである場合に限り、上向き 1 段の
     * {@link StandardVisibility#ORGANIZATION_WIDE} を下向き再帰の
     * {@link StandardVisibility#ORGANIZATION_AND_DESCENDANTS} へ昇格する。
     *
     * <p>これにより、ネスト組織の root が配信した「組織全体」スケジュールを、孫組織配下の
     * 参加チームのみに所属するメンバーまで閲覧可能にする（欠陥 Z の根治）。TEAM スコープの
     * スケジュールは従来どおり {@link StandardVisibility#ORGANIZATION_WIDE}（親 ORG への上向き
     * 1 段公開）のまま変更しない。</p>
     */
    @Override
    protected StandardVisibility adjustLevel(
            ScheduleVisibilityProjection row, StandardVisibility level) {
        StandardVisibility adjusted = level;
        if (adjusted == StandardVisibility.ORGANIZATION_WIDE
                && row.scopeType() != null
                && "ORGANIZATION".equals(row.scopeType())) {
            adjusted = StandardVisibility.ORGANIZATION_AND_DESCENDANTS;
        }
        // CMP-017b: 閲覧閾値軸（min_view_role）を scope 軸へ合成する。
        // 所有スコープの直接所属ロールで評価される段（SCOPE_AFFILIATED / 閾値ラダー）は
        // ここで «狭い方» へ引き上げれば足りる。所属拡大軸（ORGANIZATION_WIDE）は評価対象
        // スコープが親組織であり 1 値へ畳み込めないため visibleByAdditionalAxis 側で扱う。
        return MinViewRoleThreshold.tighten(adjusted, row.minViewRole());
    }

    /**
     * CMP-017b — 閲覧閾値軸（{@code min_view_role}）のうち、
     * {@link StandardVisibility#ORGANIZATION_WIDE}（組織共有）に対する評価。
     *
     * <p>設計書 {@code docs/features/F03.1_schedule_shared.md}「{@code min_view_role} の評価スコープ
     * （親子関係）」は、{@code visibility = 'ORGANIZATION'} のスケジュールの閾値を
     * <strong>親組織への直接所属ロール</strong>で評価すると定める（親グループのロールは
     * 子グループへ継承しない）。所有スコープのロールを見る
     * {@link UserScopeRoleSnapshot#hasRoleOrAbove} ではなく
     * {@link UserScopeRoleSnapshot#hasParentOrgRoleOrAbove} を用いるのはこのためである。</p>
     *
     * <p>閾値段そのものへの写像は {@link MinViewRoleThreshold} が単一正準で持ち、
     * {@link #adjustLevel} および {@code GoogleCalendarService} の push 判定と共有する。</p>
     *
     * <p><strong>{@link StandardVisibility#ORGANIZATION_AND_DESCENDANTS}（下向き再帰・組織スコープ）
     * も本フックで評価する</strong>（CMP-017b 三b）。当該段の閲覧者は子孫組織配下チームのみの
     * 所属者であり得るため、所有 ORG への直接所属ロールだけで評価すると既定 {@code MEMBER_PLUS} で
     * 一律 deny となり、下向き再帰（欠陥 Z の根治）が無効化されてしまう。そこで
     * {@link UserScopeRoleSnapshot#hasDescendantRoleOrAbove}（配下ツリーにおける実効ロール）と
     * {@link UserScopeRoleSnapshot#hasRoleOrAbove}（当該 ORG への直接所属ロール）の
     * <strong>いずれかが閾値を満たすこと</strong>を要求する。前者だけでは組織へ直接所属する
     * メンバーを取りこぼし、後者だけでは M2 を殺すため、両方の «立場» を突き合わせる。</p>
     */
    @Override
    protected boolean visibleByAdditionalAxis(
            ScheduleVisibilityProjection row, Long viewerUserId,
            UserScopeRoleSnapshot snapshot, StandardVisibility level) {
        if (level != StandardVisibility.ORGANIZATION_WIDE
                && level != StandardVisibility.ORGANIZATION_AND_DESCENDANTS) {
            return true;
        }
        String required = MinViewRoleThreshold.requiredRoleName(row.minViewRole());
        if (required == null) {
            return true;
        }
        if (row.scopeType() == null || row.scopeId() == null) {
            return false;
        }
        ScopeKey scope = new ScopeKey(row.scopeType(), row.scopeId());
        if (level == StandardVisibility.ORGANIZATION_AND_DESCENDANTS) {
            return snapshot.hasDescendantRoleOrAbove(scope, required)
                    || snapshot.hasRoleOrAbove(scope, required);
        }
        return snapshot.hasParentOrgRoleOrAbove(scope, required);
    }

    @Override
    protected ContentStatus toContentStatus(ScheduleVisibilityProjection row) {
        ScheduleStatus status = row.scheduleStatus();
        if (status == null) {
            // fail-closed: status 欠損は不可視扱い（DELETED 相当）
            return ContentStatus.DELETED;
        }
        // SCHEDULED / COMPLETED / CANCELLED いずれも通常の visibility 評価へ進める。
        return ContentStatus.PUBLISHED;
    }
}
