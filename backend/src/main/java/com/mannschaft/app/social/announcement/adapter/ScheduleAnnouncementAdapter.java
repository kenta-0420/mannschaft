package com.mannschaft.app.social.announcement.adapter;

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
                              Long scopeId, String visibility, Long userId) {
        if (content.getStartAt() == null) {
            throw new IllegalArgumentException(
                    "スケジュールチャネルには start_at が必須です");
        }

        boolean allDay = Boolean.TRUE.equals(content.getAllDay());

        CreateScheduleRequest request = new CreateScheduleRequest(
                content.getTitle(),
                content.getDescription(),   // description
                content.getLocation(),      // location
                content.getStartAt(),
                content.getEndAt(),  // null 可（終日の場合は開始日と同日）
                allDay,
                "NORMAL",            // eventType（通常イベント）
                visibility,          // ScheduleVisibility に対応する文字列
                null,                // minViewRole（デフォルト）
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
                false                // includeSupporters（既定 false）
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
}
