package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.event.TeamOrgStalePendingDetectedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 「その PENDING は、相手（組織・チーム）の最後の閉鎖より前に作られたか」の判定（F01.2.1 2-D・§4.5）。
 *
 * <p>組織・チームのアーカイブ解除・論理削除の取消（復元）では、閉鎖より前の申請・招待を復元しない。閉鎖の片付けは
 * コミット後（イベント・回復バッチ）に走ることがあるので、その前に解除されると閉鎖前の PENDING が残りうる。
 * 片付け・回復バッチ・承認・承諾は、すべて本クラスの<b>同じ規則</b>で「閉鎖前の PENDING」を見分け、無効として扱う
 * （片付けは消して取消を予約する。承認・承諾は行が無いのと同じ 404 で拒み、ACTIVE にしない。拒否・辞退・取消・取下げは行を消す操作で片付けと同じ終状態になり、操作者の明示の意思を優先するので判定しない）。</p>
 *
 * <p>閉鎖時刻（{@code organizations.last_closed_at}・{@code teams.last_closed_at}）は起きた瞬間（{@link Instant}）を
 * 秒に切り捨てて持つ。加盟行の {@code created_at} は JPA が JVM 既定ゾーン（{@link UserZoneLocalDateTimeParser#SERVER_ZONE}。
 * {@code TimeZoneConfig} が固定する）の壁時計として読み書きする {@link LocalDateTime} なので、同じゾーンで瞬間へ戻してから
 * 比べる（{@code ClockConfig#wallClock} と同じ根拠）。厳密に前（{@code created_at < last_closed_at}）の行だけを閉鎖前と
 * 見なすので、閉鎖の後に作られた行（解除後の申請・招待）は必ず閉鎖前にならない。</p>
 */
public final class TeamOrgClosureWatermark {

    private TeamOrgClosureWatermark() {
    }

    /**
     * 指定の作成日時の加盟行が、最後の閉鎖より前に作られたか。
     *
     * @param createdAt    加盟行の {@code created_at}（JPA が読んだ壁時計）
     * @param lastClosedAt 最後の閉鎖の瞬間（一度も閉じていなければ null）
     */
    public static boolean createdBefore(LocalDateTime createdAt, Instant lastClosedAt) {
        return lastClosedAt != null && createdAt != null
                && createdAt.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant().isBefore(lastClosedAt);
    }

    /**
     * 承認・承諾の前段: ロック済みの加盟行が組織またはチームの最後の閉鎖より前に作られていれば、無効な PENDING として
     * 行が無いのと同じ 404 {@code TEAM_070} で拒む（ACTIVE にしない）。トランザクションは巻き戻るので、片付け（行の削除と
     * 相手への取消の予約）は {@link TeamOrgStalePendingDetectedEvent} を受けたリスナーがロールバックの後に自分の tx で行う。
     *
     * @param row                      ロック済みの加盟行
     * @param organizationLastClosedAt 組織の最後の閉鎖の瞬間（組織の状態から。一度も閉じていなければ null）
     * @param team                     ロック済みのチーム行
     */
    static void rejectIfCreatedBeforeLastClosure(TeamOrgMembershipEntity row, Instant organizationLastClosedAt,
                                                 TeamEntity team, ApplicationEventPublisher eventPublisher) {
        if (createdBefore(row.getCreatedAt(), organizationLastClosedAt)
                || createdBefore(row.getCreatedAt(), team.getLastClosedAt())) {
            eventPublisher.publishEvent(
                    new TeamOrgStalePendingDetectedEvent(row.getTeamId(), row.getOrganizationId()));
            throw new BusinessException(TeamErrorCode.TEAM_070);
        }
    }
}
