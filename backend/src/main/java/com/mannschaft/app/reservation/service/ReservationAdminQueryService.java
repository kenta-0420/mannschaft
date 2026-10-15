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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * F10.1.1 / P1: 予約ドメインの管理者向け承認待ち集約 Query Service（read-only）。
 *
 * <p>「承認待ち」= {@code status = PENDING} の予約。件数は既存
 * {@link ReservationRepository#countByTeamIdAndStatusAndIsGroupPrimaryTrue} を流用し、プレビューは
 * {@link ReservationRepository#findByTeamIdAndStatusAndIsGroupPrimaryTrueOrderByBookedAtDesc} で LIMIT 取得する
 * （F03.4.3: グループは代表行 1 件で数える）。
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

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /** 本日の予約数に数える「有効予約」のステータス（キャンセル等は除外）。 */
    private static final List<ReservationStatus> ACTIVE_RESERVATION_STATUSES =
            List.of(ReservationStatus.CONFIRMED, ReservationStatus.PENDING);

    private final ReservationRepository reservationRepository;
    private final NameResolverService nameResolverService;

    /**
     * 指定チームの承認待ち予約（PENDING）の件数とプレビューを返す。
     *
     * @param teamId      チーム ID（WHERE 必須・IDOR 防止）
     * @param teamSlug    チーム slug（プレビュー要素の個別遷移先ルート組み立てに使用）
     * @param previewSize プレビュー件数（0 なら件数のみ）
     * @return 件数とプレビューの集計結果
     */
    @Transactional(readOnly = true)
    public PendingAggregate pendingForTeam(Long teamId, String teamSlug, int previewSize) {
        // F03.4.3 §5.6 #4/#10: グループは代表行 1 件で数え・並べる（単枠は常に TRUE で従来どおり）。
        long count = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(
                teamId, ReservationStatus.PENDING);

        if (previewSize <= 0) {
            return new PendingAggregate(count, List.of());
        }

        List<ReservationEntity> preview = reservationRepository
                .findByTeamIdAndStatusAndIsGroupPrimaryTrueOrderByBookedAtDesc(
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
                        r.getBookedAt(),
                        // その 1 件の個別遷移先（list_route の status 付き一覧とは別物・§3.1）
                        "/teams/" + teamSlug + "/admin/reservations/" + r.getId()))
                .toList();

        return new PendingAggregate(count, items);
    }

    /**
     * F10.1.1 / P3b Wave2: 指定チームの予約サマリ（承認待ち件数 / 本日の予約数）を返す
     * （管理者レンズ「予約サマリ」{@code ADMIN_TEAM_RESERVATIONS}・設計書 02 §2.2①）。
     *
     * <ul>
     *   <li>承認待ち = {@code status=PENDING} の件数（{@link #pendingForTeam} と同じ断面・件数のみ流用）。</li>
     *   <li>本日の予約数 = 本日（JST）に {@code booked_at} があり、ステータスが CONFIRMED/PENDING の有効予約
     *       （キャンセル等は除外）。本日 0:00:00 JST を UTC（DB 格納 TZ）へ変換した半開区間 [本日0:00, 翌日0:00) で絞る。</li>
     * </ul>
     *
     * <p>全クエリの WHERE に {@code team_id} を含めるため、テナント越境（IDOR）は構造的に発生しない。</p>
     *
     * @param teamId チーム ID（WHERE 必須・IDOR 防止）
     * @return 承認待ち件数・本日の予約数のドメインローカル集計
     */
    @Transactional(readOnly = true)
    public TeamReservationSummary summaryForTeam(Long teamId) {
        // F03.4.3 §5.6 #4: グループ=1 予約で数える（代表行絞り）。
        long pending = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(
                teamId, ReservationStatus.PENDING);

        // 本日（JST）の半開区間 [本日0:00, 翌日0:00) を JST→UTC へ変換して算出する（ReservationAdminAlertQueryService と同方式）。
        LocalDate todayJst = LocalDate.now(JST);
        LocalDateTime todayStartUtc = todayJst.atStartOfDay()
                .atZone(JST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime tomorrowStartUtc = todayJst.plusDays(1).atStartOfDay()
                .atZone(JST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        long today = reservationRepository.countByTeamIdAndStatusInAndBookedAtRange(
                teamId, ACTIVE_RESERVATION_STATUSES, todayStartUtc, tomorrowStartUtc);

        return new TeamReservationSummary(pending, today);
    }

    /**
     * 予約サマリのドメインローカル集計。
     *
     * @param pendingCount 承認待ち件数（status=PENDING）
     * @param todayCount   本日の予約数（本日 JST・CONFIRMED/PENDING）
     */
    public record TeamReservationSummary(long pendingCount, long todayCount) {
    }
}
