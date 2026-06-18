package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
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
        if (level == StandardVisibility.ORGANIZATION_WIDE
                && row.scopeType() != null
                && "ORGANIZATION".equals(row.scopeType())) {
            return StandardVisibility.ORGANIZATION_AND_DESCENDANTS;
        }
        return level;
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
