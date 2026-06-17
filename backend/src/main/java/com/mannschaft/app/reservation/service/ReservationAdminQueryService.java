package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * F10.1.1 / P1: 予約ドメインの管理者向け承認待ち集約 Query Service（read-only）。
 *
 * <p>「承認待ち」= {@code status = PENDING} の予約。件数は既存
 * {@link ReservationRepository#countByTeamIdAndStatus} を流用し、プレビューは
 * {@link ReservationRepository#findByTeamIdAndStatusOrderByBookedAtDesc} で LIMIT 取得する。
 * 全クエリの WHERE に {@code team_id} を含めるため、テナント越境（IDOR）は構造的に発生しない。</p>
 *
 * <p>承認ロジック・トランザクション・監査ログには一切触れない（読み取り専用）。
 * 複数ドメインをまたがないため {@code @Transactional(readOnly=true)} はドメイン内に閉じる。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/03_admin_action_required_api.md §3.4 / §4.4</p>
 */
@Service
@RequiredArgsConstructor
public class ReservationAdminQueryService {

    private final ReservationRepository reservationRepository;
    private final NameResolverService nameResolverService;

    /**
     * 指定チームの承認待ち予約（PENDING）の件数とプレビューを返す。
     *
     * @param teamId      チーム ID（WHERE 必須・IDOR 防止）
     * @param previewSize プレビュー件数（0 なら件数のみ）
     * @return 件数とプレビューの集計結果
     */
    @Transactional(readOnly = true)
    public PendingAggregate pendingForTeam(Long teamId, int previewSize) {
        long count = reservationRepository.countByTeamIdAndStatus(teamId, ReservationStatus.PENDING);

        if (previewSize <= 0) {
            return new PendingAggregate(count, List.of());
        }

        List<ReservationEntity> preview = reservationRepository
                .findByTeamIdAndStatusOrderByBookedAtDesc(
                        teamId, ReservationStatus.PENDING, PageRequest.of(0, previewSize))
                .getContent();

        Map<Long, String> names = nameResolverService.resolveUserDisplayNames(
                preview.stream().map(ReservationEntity::getUserId).toList());

        List<PendingAggregate.Item> items = preview.stream()
                .map(r -> new PendingAggregate.Item(
                        String.valueOf(r.getId()),
                        r.getUserNote() != null && !r.getUserNote().isBlank()
                                ? r.getUserNote() : "予約申請",
                        names.getOrDefault(r.getUserId(), "不明なユーザー"),
                        r.getBookedAt()))
                .toList();

        return new PendingAggregate(count, items);
    }
}
