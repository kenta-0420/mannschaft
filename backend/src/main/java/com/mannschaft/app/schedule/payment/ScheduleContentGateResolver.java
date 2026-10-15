package com.mannschaft.app.schedule.payment;

import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.spi.ContentGateResolver;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * スケジュールの課金ゲート用スコープResolver。
 */
@Component
@RequiredArgsConstructor
public class ScheduleContentGateResolver implements ContentGateResolver {

    private final ScheduleRepository scheduleRepository;

    @Override
    public String contentType() {
        return ContentGateType.SCHEDULE;
    }

    @Override
    public boolean existsInScope(Long contentId, Long teamId, Long organizationId) {
        if (teamId != null) {
            return scheduleRepository.existsByIdAndTeamId(contentId, teamId);
        }
        return organizationId != null
                && scheduleRepository.existsByIdAndOrganizationId(contentId, organizationId);
    }
}
