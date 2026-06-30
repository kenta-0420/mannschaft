package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.service.ScheduleService;
import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * F02.8 スケジュールチャネルアダプター。
 *
 * <p>{@link ScheduleService} を呼び出してスケジュールを作成し、
 * 作成されたスケジュールの ID を返す。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleAnnouncementAdapter implements AnnouncementChannelAdapter {

    private final ScheduleService scheduleService;

    @Override
    public AnnouncementSourceType getSourceType() {
        return AnnouncementSourceType.SCHEDULE;
    }

    @Override
    public Long createContent(AnnouncementContentRequest content, String scopeType,
                              Long scopeId, String targetRole, Long userId) {
        if (content.getStartAt() == null) {
            throw new IllegalArgumentException(
                    "スケジュールチャネルには start_at が必須です");
        }

        boolean allDay = Boolean.TRUE.equals(content.getAllDay());

        // 告知ウィザードの target_role（MEMBERS_AND_ABOVE 等）はスケジュールの2軸
        // （scope 軸 = ScheduleVisibility / role 軸 = MinViewRole）へ分解する。
        // 生の target_role 文字列を ScheduleVisibility.valueOf に流すと
        // IllegalArgumentException（→ COMMON_999 500）になるため、ここで変換を閉じる。
        String visibility = toScheduleVisibility(scopeType);
        String minViewRole = toMinViewRole(targetRole);

        CreateScheduleRequest request = new CreateScheduleRequest(
                content.getTitle(),
                content.getDescription(),   // description
                content.getLocation(),      // location
                content.getStartAt(),
                content.getEndAt(),  // null 可（終日の場合は開始日と同日）
                allDay,
                "EVENT",             // eventType（告知スケジュールは一般イベント扱い。有効値=PRACTICE/MATCH/EVENT/MEETING/OTHER。"NORMAL" は EventType に存在せず valueOf で 500 になる）
                visibility,          // ScheduleVisibility に対応する文字列（scope 軸）
                minViewRole,         // MinViewRole に対応する文字列（role 軸）
                null,                // minResponseRole（デフォルト）
                false,               // attendanceRequired（告知ウィザードでは false）
                null,                // attendanceDeadline
                null,                // commentOption
                null,                // eventCategoryId
                null,                // academicYear
                null,                // recurrenceRule
                null,                // surveys
                null,                // reminders
                null,                // scheduledSurveys（機能55: 告知ウィザードでは未使用）
                null,                // scheduledAttendance（機能55: 告知ウィザードでは未使用）
                false,               // includeSupporters（既定 false）
                false                // teamBreakdownEnabled（既定 false＝従来挙動）
        );

        ScheduleResponse response = scheduleService.createSchedule(
                request, scopeId, scopeType, userId);

        log.info("スケジュール作成完了 scheduleId={}, scopeType={}, scopeId={}",
                response.getId(), scopeType, scopeId);
        return response.getId();
    }

    @Override
    public String buildContentUrl(String scopeType, Long scopeId, Long contentId) {
        String scopePath = "TEAM".equalsIgnoreCase(scopeType) ? "teams" : "organizations";
        return "/" + scopePath + "/" + scopeId + "/schedules/" + contentId;
    }

    /**
     * 告知スコープ種別（TEAM / ORGANIZATION）を {@link ScheduleVisibility} の文字列へ変換する（scope 軸）。
     *
     * <p>未知 / null の場合は安全側の {@code MEMBERS_ONLY} へフォールバックする。
     * enum 越境変換をアダプター内に閉じ、{@code valueOf} 例外を外へ漏らさない。</p>
     *
     * @param scopeType 告知スコープ種別文字列
     * @return ScheduleVisibility の name 文字列
     */
    private String toScheduleVisibility(String scopeType) {
        if ("ORGANIZATION".equalsIgnoreCase(scopeType)) {
            return ScheduleVisibility.ORGANIZATION.name();
        }
        // TEAM・その他はメンバー限定（チーム告知の既定）
        return ScheduleVisibility.MEMBERS_ONLY.name();
    }

    /**
     * 告知 target_role を {@link MinViewRole} の文字列へ変換する（role 軸）。
     *
     * <p>{@code MEMBERS_AND_ABOVE → MEMBER_PLUS} / {@code SUPPORTERS_AND_ABOVE → SUPPORTER_PLUS} /
     * {@code PUBLIC → ANYONE}。未知 / null は安全側の {@code MEMBER_PLUS} へフォールバックする。</p>
     *
     * @param targetRole 告知対象ロール文字列
     * @return MinViewRole の name 文字列
     */
    private String toMinViewRole(String targetRole) {
        if (targetRole == null) {
            return MinViewRole.MEMBER_PLUS.name();
        }
        return switch (targetRole) {
            case "PUBLIC" -> MinViewRole.ANYONE.name();
            case "SUPPORTERS_AND_ABOVE" -> MinViewRole.SUPPORTER_PLUS.name();
            case "MEMBERS_AND_ABOVE" -> MinViewRole.MEMBER_PLUS.name();
            default -> MinViewRole.MEMBER_PLUS.name();
        };
    }
}
