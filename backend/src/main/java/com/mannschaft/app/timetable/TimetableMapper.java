package com.mannschaft.app.timetable;

import org.mapstruct.Mapper;

/**
 * 時間割関連の Entity ↔ DTO 変換マッパー。
 * <p>
 * 後続DTO作成後に変換メソッドを実装する。
 * </p>
 */
@Mapper(componentModel = "spring")
public interface TimetableMapper {

    // TODO: 後続部隊でDTOを作成後、以下のような変換メソッドを実装する
    // TimetableResponse toResponse(TimetableEntity entity);
    // TimetableSlotResponse toSlotResponse(TimetableSlotEntity entity);
    // TimetableChangeResponse toChangeResponse(TimetableChangeEntity entity);
    // TimetableTermResponse toTermResponse(TimetableTermEntity entity);
    // TimetablePeriodTemplateResponse toTemplateResponse(TimetablePeriodTemplateEntity entity);
}
