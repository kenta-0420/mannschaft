package com.mannschaft.app.event;

import com.mannschaft.app.event.dto.CheckinResponse;
import com.mannschaft.app.event.dto.EventDetailResponse;
import com.mannschaft.app.event.dto.EventResponse;
import com.mannschaft.app.event.dto.InviteTokenResponse;
import com.mannschaft.app.event.dto.RegistrationResponse;
import com.mannschaft.app.event.dto.TicketResponse;
import com.mannschaft.app.event.dto.TicketTypeResponse;
import com.mannschaft.app.event.dto.TimetableItemResponse;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventGuestInviteTokenEntity;
import com.mannschaft.app.event.entity.EventRegistrationEntity;
import com.mannschaft.app.event.entity.EventTicketEntity;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.entity.EventTimetableItemEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * イベント機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface EventMapper {

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    EventResponse toEventResponse(EventEntity entity);

    List<EventResponse> toEventResponseList(List<EventEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "visibility", expression = "java(entity.getVisibility().name())")
    @Mapping(target = "rsvpSummary", ignore = true)
    EventDetailResponse toEventDetailResponse(EventEntity entity);

    TicketTypeResponse toTicketTypeResponse(EventTicketTypeEntity entity);

    List<TicketTypeResponse> toTicketTypeResponseList(List<EventTicketTypeEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    RegistrationResponse toRegistrationResponse(EventRegistrationEntity entity);

    List<RegistrationResponse> toRegistrationResponseList(List<EventRegistrationEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    TicketResponse toTicketResponse(EventTicketEntity entity);

    List<TicketResponse> toTicketResponseList(List<EventTicketEntity> entities);

    @Mapping(target = "checkinType", expression = "java(entity.getCheckinType().name())")
    CheckinResponse toCheckinResponse(EventCheckinEntity entity);

    List<CheckinResponse> toCheckinResponseList(List<EventCheckinEntity> entities);

    TimetableItemResponse toTimetableItemResponse(EventTimetableItemEntity entity);

    List<TimetableItemResponse> toTimetableItemResponseList(List<EventTimetableItemEntity> entities);

    InviteTokenResponse toInviteTokenResponse(EventGuestInviteTokenEntity entity);

    List<InviteTokenResponse> toInviteTokenResponseList(List<EventGuestInviteTokenEntity> entities);
}
