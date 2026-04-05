package com.mannschaft.app.timetable;

import com.mannschaft.app.timetable.dto.PeriodTemplateResponse;
import com.mannschaft.app.timetable.dto.TimetableChangeResponse;
import com.mannschaft.app.timetable.dto.TimetableSlotResponse;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetablePeriodTemplateEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 時間割関連の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface TimetableMapper {

    @Mapping(target = "weekPattern", expression = "java(entity.getWeekPattern() != null ? entity.getWeekPattern().name() : null)")
    TimetableSlotResponse toSlotResponse(TimetableSlotEntity entity);

    @Mapping(target = "changeType", expression = "java(entity.getChangeType() != null ? entity.getChangeType().name() : null)")
    @Mapping(target = "notified", source = "notifyMembers")
    TimetableChangeResponse toChangeResponse(TimetableChangeEntity entity);

    PeriodTemplateResponse toTemplateResponse(TimetablePeriodTemplateEntity entity);
}
