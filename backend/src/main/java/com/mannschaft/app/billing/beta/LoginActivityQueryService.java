package com.mannschaft.app.billing.beta;

import com.mannschaft.app.auth.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * F20.3 ベータ特典: 個人の {@code activeDays}（アクティブ日数）計測サービス（設計書 02 §2・README §7）。
 *
 * <p><b>唯一の計測源</b>: {@code audit_logs} の {@code LOGIN_SUCCESS} を
 * {@code COUNT(DISTINCT DATE(created_at))} で数える（F10.8 {@code page_view_logs} は TEAM/ORG スコープ
 * 限定で USER を持たないため使えない・README §7）。</p>
 *
 * <p><b>クロスドメイン方針（{@code ScopeMemberCountService} と同型）</b>: 本サービスは auth ドメインの
 * {@link AuditLogRepository} を read-only 参照するが、
 * <ul>
 *   <li><b>{@code @Transactional} を付けない</b> — クラスに {@code @Transactional} が無ければ
 *       クロスドメイン {@code @Transactional} 番人（D-3）に抵触しない。呼び出し元
 *       （{@link BetaPerkEligibilityService} / {@link BetaGrantService}）の tx 境界に読み取りだけ参加する。</li>
 *   <li><b>{@code AuditLogEntity} を import しない</b> — リポジトリは scalar（{@code long}）を返すため、
 *       クロスドメイン Entity 参照番人（D-1）にも抵触しない。</li>
 * </ul></p>
 */
@Service
@RequiredArgsConstructor
public class LoginActivityQueryService {

    private final AuditLogRepository auditLogRepository;

    /**
     * 指定ユーザーの、指定日時以降のアクティブ日数（ログイン成功日の distinct DATE 数）を返す。
     *
     * @param userId 対象ユーザー（個人特典の scope_id）
     * @param since  評価ウィンドウ起点（now − evaluationWindowDays）
     * @return アクティブ日数
     */
    public long countDistinctActiveDays(Long userId, LocalDateTime since) {
        if (userId == null || since == null) {
            return 0L;
        }
        return auditLogRepository.countDistinctLoginDaysSince(userId, since);
    }
}
