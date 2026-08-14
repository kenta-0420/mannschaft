package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.RecursionDepthCounter;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F00 {@link ReferenceType#SCHEDULE_COMMENT} の可視性判定 Resolver（F03.16 §4.5 / §4.5.0）。
 *
 * <h2>判定は親予定へ完全委譲する — 独自の閲覧述語を書かない【マスター御裁可 2026-08-11】</h2>
 * <p>コメントは可視性列を一切持たない。閲覧可否は<b>親スケジュールの可視性判定に完全に一致</b>し、
 * その判定は {@link ContentVisibilityChecker#canView(ReferenceType, Long, Long)}
 * （およびそのバッチ版 {@link ContentVisibilityChecker#filterAccessible}）<b>単体</b>で行う。</p>
 *
 * <p><b>なぜ独自述語を新設しないのか</b>: CMP-017b（PR #2705 / {@code ae4c3d626}）により
 * {@code ScheduleVisibilityProjection} に {@code minViewRole} が載り、
 * {@link ScheduleVisibilityResolver} が {@link MinViewRoleThreshold#tighten} 経由で
 * それを {@code canView} の判定に内部合成済みである。すなわち {@code canView} を呼ぶだけで
 * F00 可視性ラダー（所属・{@code ORGANIZATION} 昇格・{@code CUSTOM_TEMPLATE}）に加えて
 * {@code min_view_role} 閾値まで一度に評価される。ここで別の写像を書くと
 * <b>閾値の写像が二重化</b>し、{@link MinViewRoleThreshold} の Javadoc が名指しで禁じる形
 * ＝ CMP-017b の事故そのものを再生産する。</p>
 *
 * <h2>金型（{@code CirculationCommentVisibilityResolver}）から意図的に外れる 2 点</h2>
 * <ol>
 *   <li><b>{@code AbstractContentVisibilityResolver} を継承しない</b>。同基底は
 *       {@code loadProjections(Collection<Long>)} が示すとおり <b>BIGINT 主キー専用</b>だが、
 *       {@code schedule_comments} は UUIDv7 主キー（設計書 §1.6 の裁定変更）である。
 *       よって {@link ContentVisibilityResolver} を直接実装し UUID 経路
 *       （{@link #canViewUuid} / {@link #filterAccessibleUuid}）を本流とする
 *       （{@code ScheduleKeepVisibilityResolver} / {@code MatchVisibilityResolver} と同じ事情・同じ形）。
 *       設計書 §4.5 のコードスケッチは基底継承で書かれているが、あれは §1.6 の UUID 化裁定より
 *       前の記述であり、本実装が正である。</li>
 *   <li><b>{@link ReferenceType#COMMENT} を流用しない</b>。同値は
 *       {@code CirculationCommentVisibilityResolver} が既に専有しており、同じ値を 2 つの
 *       Resolver が返すとディスパッチ表の解決が一意に定まらない（詳細は
 *       {@link ReferenceType#SCHEDULE_COMMENT} の Javadoc）。</li>
 * </ol>
 *
 * <p><b>循環依存対策</b>: {@link ContentVisibilityChecker} は全 Resolver Bean を constructor で
 * 集約するため、直接 inject すると循環依存になる。{@code @Lazy} で proxy 経由の遅延解決とする
 * （金型どおり）。</p>
 *
 * <p><b>再帰深度対策</b>: 親（SCHEDULE）への委譲は Checker の再入であるため、
 * {@link RecursionDepthCounter#enter()} / {@code exit()} で挟む（F00 §D-16・深度上限 3）。</p>
 *
 * <p><b>論理削除の扱い</b>: {@code schedule_comments} は {@code @SQLRestriction} を持たない
 * （トゥームストーン表示が一覧の本流のため・設計書 §3.3 の裁定）。可視性判定に削除有無は
 * 関係しない（削除済みコメントもトゥームストーンとして「親が見える人には見える」）ので、
 * 射影は削除済みを含めて引き、ここでは {@code deleted_at} を条件にしない。
 * 削除済みを応答に載せるか否かは §5.3 のトゥームストーン述語＝Service 層の責務である。</p>
 */
@Slf4j
@Component
public class ScheduleCommentVisibilityResolver implements ContentVisibilityResolver<Enum<?>> {

    private final ScheduleCommentRepository scheduleCommentRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final RecursionDepthCounter recursionDepthCounter;

    public ScheduleCommentVisibilityResolver(
            ScheduleCommentRepository scheduleCommentRepository,
            @Lazy ContentVisibilityChecker contentVisibilityChecker,
            RecursionDepthCounter recursionDepthCounter) {
        this.scheduleCommentRepository = scheduleCommentRepository;
        this.contentVisibilityChecker = contentVisibilityChecker;
        this.recursionDepthCounter = recursionDepthCounter;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.SCHEDULE_COMMENT;
    }

    // ─── Long 経路（未使用・fail-closed） ─────────────────────────
    // schedule_comments の主キーは UUIDv7 のため Long 経路は成立しない。
    // 例外ではなく false / 空集合に倒す（呼び違いで 500 を出すより不可視に倒す方が安全）。

    @Override
    public boolean canView(Long contentId, Long viewerUserId) {
        log.warn("SCHEDULE_COMMENT resolver called via Long path (should be UUID): contentId={}", contentId);
        return false;
    }

    @Override
    public Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId) {
        log.warn("SCHEDULE_COMMENT resolver called via Long batch path (should be UUID)");
        return Collections.emptySet();
    }

    // ─── UUID 経路（本流） ───────────────────────────────────────

    @Override
    public boolean canViewUuid(UUID contentId, Long viewerUserId) {
        if (contentId == null) {
            return false;
        }
        return filterAccessibleUuid(List.of(contentId), viewerUserId).contains(contentId);
    }

    /**
     * バッチ判定。<b>SQL は 2 本以内</b>（設計書 AC-30 / F00 の要件）。
     *
     * <ol>
     *   <li>SQL 1 — {@code schedule_comments} から {@code (id, schedule_id)} を 1 回で射影取得。</li>
     *   <li>SQL 2 段 — <b>重複排除した親 {@code scheduleId} 集合に対して
     *       {@link ContentVisibilityChecker#filterAccessible} を 1 回だけ</b>呼ぶ。
     *       コメント 1 件ずつ {@code canView} を呼ぶと N+1 になるため、必ずバッチ側を使う
     *       （委譲先が同じ F00 正準である以上、単発版と判定結果は完全に一致する）。
     *       一覧は単一予定なので実質 {@code scheduleId} 1 件の判定に畳まれる（§10.1）。</li>
     * </ol>
     */
    @Override
    public Set<UUID> filterAccessibleUuid(Collection<UUID> contentIds, Long viewerUserId) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Collections.emptySet();
        }

        // SQL 1: 実存確認込みの射影取得（不在＝deny。存在を漏らさない）。
        List<ScheduleCommentVisibilityProjection> rows =
                scheduleCommentRepository.findVisibilityProjectionsByIdIn(new LinkedHashSet<>(contentIds));
        if (rows == null || rows.isEmpty()) {
            return Collections.emptySet();
        }

        // 親 scheduleId を重複排除（同一予定の 20 コメントを 1 回の判定に畳む）。
        Set<Long> scheduleIds = new LinkedHashSet<>();
        for (ScheduleCommentVisibilityProjection row : rows) {
            if (row != null && row.getScheduleId() != null) {
                scheduleIds.add(row.getScheduleId());
            }
        }
        if (scheduleIds.isEmpty()) {
            return Collections.emptySet();
        }

        // SQL 2 段: 親予定への委譲を 1 回だけ。再帰深度ガードで挟む（F00 §D-16）。
        Set<Long> visibleScheduleIds;
        recursionDepthCounter.enter();
        try {
            visibleScheduleIds =
                    contentVisibilityChecker.filterAccessible(ReferenceType.SCHEDULE, scheduleIds, viewerUserId);
        } finally {
            recursionDepthCounter.exit();
        }
        if (visibleScheduleIds == null || visibleScheduleIds.isEmpty()) {
            return Collections.emptySet();
        }

        Set<UUID> accessible = new HashSet<>();
        for (ScheduleCommentVisibilityProjection row : rows) {
            if (row != null && row.getId() != null && visibleScheduleIds.contains(row.getScheduleId())) {
                accessible.add(row.getId());
            }
        }
        return accessible;
    }

    /**
     * 1 行分の可視性判定 — 親予定への委譲そのもの（設計書 §4.5 の {@code evaluateCustom}・AC-36）。
     *
     * <p>{@link #filterAccessibleUuid} は N+1 回避のためバッチ版へ畳んでいるが、
     * <b>「1 行をどう判定するか」の定義はこのメソッドが正</b>である。設計書 §4.5 が
     * {@code evaluateCustom} の名で規定した契約（AC-36 の fail-closed）をここで満たす。</p>
     *
     * <p><b>fail-closed</b>: {@code row} が null／{@code row.getScheduleId()} が null なら
     * <b>false</b>（例外を投げて 500 にしたり true に倒したりしない）。
     * {@code viewerUserId} が null／スケジュール不存在／{@code minViewRole} が null／
     * ロール解決失敗の fail-closed は {@link ScheduleVisibilityResolver} ／
     * {@link MinViewRoleThreshold#satisfies} 側の既存契約であり、ここで重ねて null 分岐を書かない
     * （書くと「どちらが本当の門番か」が曖昧になる・設計書 §4.5.0）。</p>
     *
     * @param row          コメントの軽量射影（{@code null} 可）
     * @param viewerUserId 閲覧者（{@code null} 可＝未認証）
     * @return 閲覧可能なら true
     */
    boolean evaluateCustom(ScheduleCommentVisibilityProjection row, Long viewerUserId) {
        if (row == null || row.getScheduleId() == null) {
            return false;
        }
        recursionDepthCounter.enter();
        try {
            return contentVisibilityChecker.canView(
                    ReferenceType.SCHEDULE, row.getScheduleId(), viewerUserId);
        } finally {
            recursionDepthCounter.exit();
        }
    }
}
