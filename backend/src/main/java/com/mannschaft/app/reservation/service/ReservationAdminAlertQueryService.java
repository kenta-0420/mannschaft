package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * F10.1.1 / P3b: 予約ドメインの管理者レンズ「業務アラート」用 Query Service（read-only・チームスコープ専用）。
 *
 * <p>チームパネル管理者レンズ ⑤（{@code ADMIN_TEAM_ALERT}）の「新規予約」件数
 * （本日 JST 0:00 以降に入った CONFIRMED 予約）を 1 スコープ分だけ集計する。
 * 全所属横断の {@code AdminBusinessAlertService}（F10.7）と<b>別 Bean</b>で、こちらは「いま見ている
 * チーム 1 件」に絞る（設計書 02 §3）。承認待ち（pending）は P1 集約 API に一本化するため本サービスは
 * 扱わない（二重計上回避・設計書 02 §3）。</p>
 *
 * <p>全クエリの WHERE に {@code team_id = ?} を含めるため、テナント越境（IDOR）は構造的に発生しない。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2 ⑤ / §3</p>
 */
@Service
@RequiredArgsConstructor
public class ReservationAdminAlertQueryService {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final ReservationRepository reservationRepository;

    /**
     * 指定チームの「新規予約」件数（本日 JST 0:00 以降に作成された CONFIRMED 予約）を返す。
     *
     * @param teamId チーム ID（WHERE 必須・IDOR 防止）
     * @return 本日の新規 CONFIRMED 予約件数
     */
    @Transactional(readOnly = true)
    public long newReservationsForTeam(Long teamId) {
        // 本日 0:00:00 JST を UTC（DB 格納タイムゾーン）に変換して下限とする（F10.7 と同方式）。
        LocalDateTime todayStartJst = LocalDate.now(JST).atStartOfDay();
        LocalDateTime todayStartUtc = todayStartJst.atZone(JST)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return reservationRepository.countByTeamIdAndStatusAndCreatedAtGreaterThanEqual(
                teamId, ReservationStatus.CONFIRMED, todayStartUtc);
    }
}
