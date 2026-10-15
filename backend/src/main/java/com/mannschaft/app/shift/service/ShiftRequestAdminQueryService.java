package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F10.1.1 / P1: シフトドメインの管理者向け承認待ち集約 Query Service（read-only）。
 *
 * <p>「承認待ち」= OPEN のシフト変更依頼（{@link ChangeRequestStatus#OPEN}）と
 * PENDING のシフト交代申請（{@link SwapRequestStatus#PENDING}）の合算を team 単位で集約する。
 * 既存は scheduleId 単位 / status 単位のクエリしか無いため、scheduleId（変更依頼）/
 * slotId→schedule（交代申請）経由で {@code schedule.team_id} を JOIN する team 単位クエリを
 * リポジトリに新設して使う（N+1 回避・WHERE team_id 必須で IDOR 防止）。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/03_admin_action_required_api.md §3.4 / §4.4</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftRequestAdminQueryService {

    private final ShiftChangeRequestRepository changeRequestRepository;
    private final ShiftSwapRequestRepository swapRequestRepository;
    private final NameResolverService nameResolverService;

    /**
     * 指定チームの承認待ちシフトリクエスト（OPEN 変更依頼 + PENDING 交代申請）の件数とプレビューを返す。
     *
     * @param teamId      チーム ID（WHERE 必須・IDOR 防止）
     * @param teamSlug    チーム slug（プレビュー要素の個別遷移先ルート組み立てに使用）
     * @param previewSize プレビュー件数（0 なら件数のみ）
     * @return 件数とプレビューの集計結果
     */
    @Transactional(readOnly = true)
    public PendingAggregate pendingForTeam(Long teamId, String teamSlug, int previewSize) {
        long changeCount = changeRequestRepository.countPendingByTeam(teamId, ChangeRequestStatus.OPEN);
        long swapCount = swapRequestRepository.countPendingByTeam(teamId, SwapRequestStatus.PENDING);
        long total = changeCount + swapCount;

        if (previewSize <= 0) {
            return new PendingAggregate(total, List.of());
        }

        PageRequest page = PageRequest.of(0, previewSize);
        List<ShiftChangeRequestEntity> changes = changeRequestRepository
                .findPendingByTeam(teamId, ChangeRequestStatus.OPEN, page);
        List<ShiftSwapRequestEntity> swaps = swapRequestRepository
                .findPendingByTeam(teamId, SwapRequestStatus.PENDING, page);

        // 申請者表示名をバルク解決（N+1 回避）
        Set<Long> userIds = new LinkedHashSet<>();
        changes.forEach(c -> userIds.add(c.getRequestedBy()));
        swaps.forEach(s -> userIds.add(s.getRequesterId()));
        Map<Long, String> names = nameResolverService.resolveUserDisplayNames(userIds);

        // 2 種別を作成日時降順でマージし、上位 previewSize 件に丸める。
        // id は対象ドメインの主キー文字列（設計書 03 §3.3）。種別（変更依頼/交代申請）は
        // detail_route のパス（/shifts/change/{id} と /shifts/swap/{id}）で区別する（合成 id を使わない）。
        List<Holder> merged = new ArrayList<>();
        changes.forEach(c -> merged.add(new Holder(
                String.valueOf(c.getId()),
                "シフト変更依頼",
                names.getOrDefault(c.getRequestedBy(), "不明なユーザー"),
                c.getCreatedAt(),
                "/teams/" + teamSlug + "/admin/shifts/change/" + c.getId())));
        swaps.forEach(s -> merged.add(new Holder(
                String.valueOf(s.getId()),
                "シフト交代申請",
                names.getOrDefault(s.getRequesterId(), "不明なユーザー"),
                s.getCreatedAt(),
                "/teams/" + teamSlug + "/admin/shifts/swap/" + s.getId())));
        merged.sort(Comparator.comparing(
                Holder::requestedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        List<PendingAggregate.Item> items = merged.stream()
                .limit(previewSize)
                .map(h -> new PendingAggregate.Item(
                        h.id(), h.title(), h.requestedBy(), h.requestedAt(), h.detailRoute()))
                .toList();

        return new PendingAggregate(total, items);
    }

    /** 2 種別のプレビューをマージするための一時保持レコード。 */
    private record Holder(String id, String title, String requestedBy, LocalDateTime requestedAt,
                          String detailRoute) {
    }
}
