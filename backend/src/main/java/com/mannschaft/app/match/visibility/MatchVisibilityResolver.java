package com.mannschaft.app.match.visibility;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F00 {@code ReferenceType.MATCH} の可視性判定 Resolver（F08.10・03 §C.3.2）。
 *
 * <p><b>独自 visibility 述語を書かず F00 正準（{@link ContentVisibilityResolver} /
 * {@code ContentVisibilityChecker}）へ寄せる</b>（メモリ教訓「可視性は必ず F00 経由」）。
 * {@code @Component} で Spring が収集し、{@code ContentVisibilityChecker} が
 * {@link #referenceType()} をキーに自動登録する。</p>
 *
 * <p>matches は UUIDv7 主キーのため <b>UUID 経路</b>（{@link #canViewUuid} /
 * {@link #filterAccessibleUuid}）を実装し、Long 経路はデフォルトのまま fail-closed とする
 * （{@code idKind()==UUID_V7}・03 §C.3.2）。</p>
 *
 * <p><b>可視性ルール（MVP）</b>: 1 試合は両チーム・各選手に統合表示する設計（03 §C.2）ゆえ、
 * 閲覧可否は「閲覧者が当該試合に関係する scope（主体チーム・相手チーム・主催組織）のメンバー以上か」で判定する。
 * 練習試合のチーム可視性レベル（SCOPE_AFFILIATED 等）に基づく公開閲覧の細分は後続フェーズで拡張する余地を残す
 * （本 MVP は所属メンバーに開示・非メンバーは fail-closed）。SystemAdmin は実存確認後に許可する。</p>
 *
 * <p>削除済み match は {@link MatchRepository} の {@code @SQLRestriction("deleted_at IS NULL")}
 * により取得されないため、不在として fail-closed になる（存在を漏らさない）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/03_permissions_and_recording_modes.md §C.3.2</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchVisibilityResolver implements ContentVisibilityResolver<Enum<?>> {

    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORG = "ORGANIZATION";

    private final MatchRepository matchRepository;
    private final AccessControlService accessControlService;

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.MATCH;
    }

    // ─── Long 経路（未使用・fail-closed） ─────────────────────────

    @Override
    public boolean canView(Long contentId, Long viewerUserId) {
        // MATCH は UUIDv7 経路専用。Long 経路は呼ばれない想定で fail-closed。
        log.warn("MATCH resolver called via Long path (should be UUID): contentId={}", contentId);
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
            return false;
        }
        // テナント絞り込み無しで取得（可視性判定はテナント越境も含めて関係性で判定する）。
        // @SQLRestriction により削除済みは取得されない。
        Optional<MatchEntity> opt = matchRepository.findById(contentId);
        return opt.isPresent() && isAuthorized(opt.get(), viewerUserId);
    }

    @Override
    public Set<UUID> filterAccessibleUuid(Collection<UUID> contentIds, Long viewerUserId) {
        if (contentIds == null || contentIds.isEmpty() || viewerUserId == null) {
            return Collections.emptySet();
        }
        // 1 SQL で一括取得し、メモリ上で関係性判定（SQL 数 ≦ 2 の契約・N+1 回避）。
        List<MatchEntity> rows = matchRepository.findAllById(contentIds);
        Set<UUID> accessible = new HashSet<>();
        for (MatchEntity match : rows) {
            if (match == null || match.getId() == null) {
                continue;
            }
            if (isAuthorized(match, viewerUserId)) {
                accessible.add(match.getId());
            }
        }
        return accessible;
    }

    /**
     * 閲覧者が当該試合の関係 scope（主体チーム・相手チーム・主催組織）のメンバー以上か判定する。
     * SystemAdmin は実存確認後に常に許可する。
     */
    private boolean isAuthorized(MatchEntity match, Long viewerUserId) {
        // SystemAdmin は実在する match に対して常に許可（Checker は高速パスを持たない設計ゆえ Resolver 側で判定）
        if (accessControlService.isSystemAdmin(viewerUserId)) {
            return true;
        }
        // 主体チームのメンバー以上
        if (match.getTeamId() != null
                && accessControlService.isMember(viewerUserId, match.getTeamId(), SCOPE_TEAM)) {
            return true;
        }
        // 相手チーム（登録済みの場合）のメンバー以上（共同記録で相手チームも閲覧する）
        if (match.getOpponentTeamId() != null
                && accessControlService.isMember(viewerUserId, match.getOpponentTeamId(), SCOPE_TEAM)) {
            return true;
        }
        // 主催組織（大会/リーグ）のメンバー以上
        if (match.getOrganizationId() != null
                && accessControlService.isMember(viewerUserId, match.getOrganizationId(), SCOPE_ORG)) {
            return true;
        }
        return false;
    }
}
