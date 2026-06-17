package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.matching.MatchProposalStatus;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * F10.1.1 / P1: マッチングドメインの管理者向け承認待ち集約 Query Service（read-only）。
 *
 * <p>「承認待ち」= 自チームの募集（受け手）に届いた {@link MatchProposalStatus#PENDING} の応募。
 * 既存は応募者視点（{@code proposingTeamId}）のクエリしか無いため、{@code requestId} →
 * {@code MatchRequestEntity.teamId} を JOIN する募集側（受け手）視点の team 単位クエリを
 * リポジトリに新設して使う（WHERE team_id 必須で IDOR 防止）。</p>
 *
 * <p>応募の主体はチーム（個人ユーザーではない）ため、{@code requested_by} には応募元チーム名を
 * バルク解決して入れる（N+1 回避）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/03_admin_action_required_api.md §3.4 / §4.4</p>
 */
@Service
@RequiredArgsConstructor
public class MatchingAdminQueryService {

    private final MatchProposalRepository matchProposalRepository;
    private final NameResolverService nameResolverService;

    /**
     * 指定チームの募集に届いた PENDING の応募の件数とプレビューを返す（受け手視点）。
     *
     * @param teamId      チーム ID（募集を出したチーム・WHERE 必須・IDOR 防止）
     * @param previewSize プレビュー件数（0 なら件数のみ）
     * @return 件数とプレビューの集計結果
     */
    @Transactional(readOnly = true)
    public PendingAggregate pendingReceivedForTeam(Long teamId, int previewSize) {
        long count = matchProposalRepository.countPendingReceivedByTeam(teamId, MatchProposalStatus.PENDING);

        if (previewSize <= 0) {
            return new PendingAggregate(count, List.of());
        }

        List<MatchProposalEntity> preview = matchProposalRepository
                .findPendingReceivedByTeam(teamId, MatchProposalStatus.PENDING, PageRequest.of(0, previewSize));

        // 応募元チーム名をバルク解決（N+1 回避）
        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(
                preview.stream().map(MatchProposalEntity::getProposingTeamId).toList());

        List<PendingAggregate.Item> items = preview.stream()
                .map(p -> new PendingAggregate.Item(
                        String.valueOf(p.getId()),
                        p.getMessage() != null && !p.getMessage().isBlank()
                                ? p.getMessage() : "練習試合の申込",
                        teamNames.getOrDefault(p.getProposingTeamId(), "不明なチーム"),
                        p.getCreatedAt()))
                .toList();

        return new PendingAggregate(count, items);
    }
}
