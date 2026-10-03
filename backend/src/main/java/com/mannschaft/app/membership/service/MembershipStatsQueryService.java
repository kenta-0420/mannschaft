package com.mannschaft.app.membership.service;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * F10.1.1 / P3b Wave2: メンバーシップドメインの管理者レンズ「メンバー統計」用 Query Service（read-only）。
 *
 * <p>管理者レンズ「メンバー統計」（{@code ADMIN_TEAM_MEMBERS} / {@code ADMIN_ORG_MEMBERS}・設計書 02 §2.2④ / §2.3④）の
 * 母集合を memberships（{@code left_at IS NULL}＝在籍の真実の源）単独で集計する。user_roles を UNION しない
 * （昇格で created_at がリセットされ「今月新規」を誤カウントするため有害）。</p>
 *
 * <p><b>ドメイン境界厳守</b>: 「アクティブ」（{@code users.status='ACTIVE'}）判定は user(auth) ドメインの責務であり、
 * 本サービスは users を直接参照しない。在籍者の user_id 集合だけを {@link MemberStats#activeUserIds()} で返し、
 * status 判定は呼び出し側（dashboard ファサード）が user ドメインに委ねる。</p>
 *
 * <p>全クエリの WHERE に {@code scope_type = ? AND scope_id = ?} を含めるため、テナント越境（IDOR）は
 * 構造的に発生しない。複数ドメインをまたがないため {@code @Transactional(readOnly=true)} はドメイン内に閉じる。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2④ / §2.3④</p>
 */
@Service
@RequiredArgsConstructor
public class MembershipStatsQueryService {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final MembershipRepository membershipRepository;

    /**
     * 指定スコープのメンバー統計（総数・今月新規・在籍 user_id 集合）を集計する。
     *
     * <p>「今月新規」は当月（JST）の初日 0:00 を下限、翌月の初日 0:00 を上限とする半開区間
     * {@code [当月初日, 翌月初日)} で {@code joined_at} を絞る。境界は JST で算出し UTC（DB 格納 TZ）へ変換する。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID（WHERE 必須・IDOR 防止）
     * @return 総数・今月新規・在籍 user_id 集合のドメインローカル集計
     */
    @Transactional(readOnly = true)
    public MemberStats statsForScope(ScopeType scopeType, Long scopeId) {
        long totalCount = membershipRepository.countActiveDistinctUsersByScope(scopeType, scopeId);

        // 当月（JST）の半開区間 [当月初日, 翌月初日) を JST→UTC へ変換して算出する。
        LocalDate todayJst = LocalDate.now(JST);
        LocalDate firstOfMonthJst = todayJst.withDayOfMonth(1);
        LocalDate firstOfNextMonthJst = firstOfMonthJst.plusMonths(1);
        LocalDateTime monthStartUtc = firstOfMonthJst.atStartOfDay()
                .atZone(JST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime monthEndUtc = firstOfNextMonthJst.atStartOfDay()
                .atZone(JST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        long newThisMonthCount = membershipRepository
                .countActiveDistinctUsersByScopeAndJoinedAtBetween(
                        scopeType, scopeId, monthStartUtc, monthEndUtc);

        List<Long> activeUserIds = membershipRepository
                .findActiveDistinctUserIdsByScope(scopeType, scopeId);

        return new MemberStats(totalCount, newThisMonthCount, activeUserIds);
    }

    /**
     * メンバー統計のドメインローカル集計。
     *
     * @param totalCount       会員総数（active な DISTINCT user_id 件数・管理者含む）
     * @param newThisMonthCount 今月新規会員数（joined_at が当月の DISTINCT user_id 件数）
     * @param activeUserIds    在籍者の user_id 集合（users.status 判定は呼び出し側が user ドメインへ委ねる）
     */
    public record MemberStats(long totalCount, long newThisMonthCount, List<Long> activeUserIds) {
    }
}
