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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:08+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class EventMapperImpl implements EventMapper {

    @Override
    public EventResponse toEventResponse(EventEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String slug = null;
        String subtitle = null;
        String coverImageKey = null;
        Boolean isPublic = null;
        LocalDateTime registrationStartsAt = null;
        LocalDateTime registrationEndsAt = null;
        Integer maxCapacity = null;
        Integer registrationCount = null;
        Integer checkinCount = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        slug = entity.getSlug();
        subtitle = entity.getSubtitle();
        coverImageKey = entity.getCoverImageKey();
        isPublic = entity.getIsPublic();
        registrationStartsAt = entity.getRegistrationStartsAt();
        registrationEndsAt = entity.getRegistrationEndsAt();
        maxCapacity = entity.getMaxCapacity();
        registrationCount = entity.getRegistrationCount();
        checkinCount = entity.getCheckinCount();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();
        String status = entity.getStatus().name();

        EventResponse eventResponse = new EventResponse( id, scopeType, scopeId, slug, subtitle, coverImageKey, status, isPublic, registrationStartsAt, registrationEndsAt, maxCapacity, registrationCount, checkinCount, createdAt, updatedAt );

        return eventResponse;
    }

    @Override
    public List<EventResponse> toEventResponseList(List<EventEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<EventResponse> list = new ArrayList<EventResponse>( entities.size() );
        for ( EventEntity eventEntity : entities ) {
            list.add( toEventResponse( eventEntity ) );
        }

        return list;
    }

    @Override
    public EventDetailResponse toEventDetailResponse(EventEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        Long scheduleId = null;
        String slug = null;
        String subtitle = null;
        String summary = null;
        String coverImageKey = null;
        String venueName = null;
        String venueAddress = null;
        BigDecimal venueLatitude = null;
        BigDecimal venueLongitude = null;
        String venueAccessInfo = null;
        Boolean isPublic = null;
        String minRegistrationRole = null;
        LocalDateTime registrationStartsAt = null;
        LocalDateTime registrationEndsAt = null;
        Integer maxCapacity = null;
        Boolean isApprovalRequired = null;
        Long postSurveyId = null;
        Long workflowRequestId = null;
        String ogpTitle = null;
        String ogpDescription = null;
        String ogpImageKey = null;
        Integer registrationCount = null;
        Integer checkinCount = null;
        Long createdBy = null;
        Long version = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        scheduleId = entity.getScheduleId();
        slug = entity.getSlug();
        subtitle = entity.getSubtitle();
        summary = entity.getSummary();
        coverImageKey = entity.getCoverImageKey();
        venueName = entity.getVenueName();
        venueAddress = entity.getVenueAddress();
        venueLatitude = entity.getVenueLatitude();
        venueLongitude = entity.getVenueLongitude();
        venueAccessInfo = entity.getVenueAccessInfo();
        isPublic = entity.getIsPublic();
        minRegistrationRole = entity.getMinRegistrationRole();
        registrationStartsAt = entity.getRegistrationStartsAt();
        registrationEndsAt = entity.getRegistrationEndsAt();
        maxCapacity = entity.getMaxCapacity();
        isApprovalRequired = entity.getIsApprovalRequired();
        postSurveyId = entity.getPostSurveyId();
        workflowRequestId = entity.getWorkflowRequestId();
        ogpTitle = entity.getOgpTitle();
        ogpDescription = entity.getOgpDescription();
        ogpImageKey = entity.getOgpImageKey();
        registrationCount = entity.getRegistrationCount();
        checkinCount = entity.getCheckinCount();
        createdBy = entity.getCreatedBy();
        version = entity.getVersion();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String scopeType = entity.getScopeType().name();
        String status = entity.getStatus().name();

        EventDetailResponse eventDetailResponse = new EventDetailResponse( id, scopeType, scopeId, scheduleId, slug, subtitle, summary, coverImageKey, venueName, venueAddress, venueLatitude, venueLongitude, venueAccessInfo, status, isPublic, minRegistrationRole, registrationStartsAt, registrationEndsAt, maxCapacity, isApprovalRequired, postSurveyId, workflowRequestId, ogpTitle, ogpDescription, ogpImageKey, registrationCount, checkinCount, createdBy, version, createdAt, updatedAt );

        return eventDetailResponse;
    }

    @Override
    public TicketTypeResponse toTicketTypeResponse(EventTicketTypeEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long eventId = null;
        String name = null;
        String description = null;
        BigDecimal price = null;
        String currency = null;
        Integer maxQuantity = null;
        Integer issuedCount = null;
        String minRegistrationRole = null;
        Boolean isActive = null;
        Integer sortOrder = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        eventId = entity.getEventId();
        name = entity.getName();
        description = entity.getDescription();
        price = entity.getPrice();
        currency = entity.getCurrency();
        maxQuantity = entity.getMaxQuantity();
        issuedCount = entity.getIssuedCount();
        minRegistrationRole = entity.getMinRegistrationRole();
        isActive = entity.getIsActive();
        sortOrder = entity.getSortOrder();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        TicketTypeResponse ticketTypeResponse = new TicketTypeResponse( id, eventId, name, description, price, currency, maxQuantity, issuedCount, minRegistrationRole, isActive, sortOrder, createdAt, updatedAt );

        return ticketTypeResponse;
    }

    @Override
    public List<TicketTypeResponse> toTicketTypeResponseList(List<EventTicketTypeEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TicketTypeResponse> list = new ArrayList<TicketTypeResponse>( entities.size() );
        for ( EventTicketTypeEntity eventTicketTypeEntity : entities ) {
            list.add( toTicketTypeResponse( eventTicketTypeEntity ) );
        }

        return list;
    }

    @Override
    public RegistrationResponse toRegistrationResponse(EventRegistrationEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long eventId = null;
        Long userId = null;
        Long ticketTypeId = null;
        String guestName = null;
        String guestEmail = null;
        String guestPhone = null;
        Integer quantity = null;
        String note = null;
        Long approvedBy = null;
        LocalDateTime approvedAt = null;
        LocalDateTime cancelledAt = null;
        String cancelReason = null;
        Long inviteTokenId = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        eventId = entity.getEventId();
        userId = entity.getUserId();
        ticketTypeId = entity.getTicketTypeId();
        guestName = entity.getGuestName();
        guestEmail = entity.getGuestEmail();
        guestPhone = entity.getGuestPhone();
        quantity = entity.getQuantity();
        note = entity.getNote();
        approvedBy = entity.getApprovedBy();
        approvedAt = entity.getApprovedAt();
        cancelledAt = entity.getCancelledAt();
        cancelReason = entity.getCancelReason();
        inviteTokenId = entity.getInviteTokenId();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();

        RegistrationResponse registrationResponse = new RegistrationResponse( id, eventId, userId, ticketTypeId, guestName, guestEmail, guestPhone, status, quantity, note, approvedBy, approvedAt, cancelledAt, cancelReason, inviteTokenId, createdAt, updatedAt );

        return registrationResponse;
    }

    @Override
    public List<RegistrationResponse> toRegistrationResponseList(List<EventRegistrationEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<RegistrationResponse> list = new ArrayList<RegistrationResponse>( entities.size() );
        for ( EventRegistrationEntity eventRegistrationEntity : entities ) {
            list.add( toRegistrationResponse( eventRegistrationEntity ) );
        }

        return list;
    }

    @Override
    public TicketResponse toTicketResponse(EventTicketEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long registrationId = null;
        Long eventId = null;
        Long ticketTypeId = null;
        String qrToken = null;
        String ticketNumber = null;
        LocalDateTime usedAt = null;
        LocalDateTime cancelledAt = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        registrationId = entity.getRegistrationId();
        eventId = entity.getEventId();
        ticketTypeId = entity.getTicketTypeId();
        qrToken = entity.getQrToken();
        ticketNumber = entity.getTicketNumber();
        usedAt = entity.getUsedAt();
        cancelledAt = entity.getCancelledAt();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();

        TicketResponse ticketResponse = new TicketResponse( id, registrationId, eventId, ticketTypeId, qrToken, ticketNumber, status, usedAt, cancelledAt, createdAt, updatedAt );

        return ticketResponse;
    }

    @Override
    public List<TicketResponse> toTicketResponseList(List<EventTicketEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TicketResponse> list = new ArrayList<TicketResponse>( entities.size() );
        for ( EventTicketEntity eventTicketEntity : entities ) {
            list.add( toTicketResponse( eventTicketEntity ) );
        }

        return list;
    }

    @Override
    public CheckinResponse toCheckinResponse(EventCheckinEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long eventId = null;
        Long ticketId = null;
        Long checkedInBy = null;
        LocalDateTime checkedInAt = null;
        String note = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        eventId = entity.getEventId();
        ticketId = entity.getTicketId();
        checkedInBy = entity.getCheckedInBy();
        checkedInAt = entity.getCheckedInAt();
        note = entity.getNote();
        createdAt = entity.getCreatedAt();

        String checkinType = entity.getCheckinType().name();

        CheckinResponse checkinResponse = new CheckinResponse( id, eventId, ticketId, checkinType, checkedInBy, checkedInAt, note, createdAt );

        return checkinResponse;
    }

    @Override
    public List<CheckinResponse> toCheckinResponseList(List<EventCheckinEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CheckinResponse> list = new ArrayList<CheckinResponse>( entities.size() );
        for ( EventCheckinEntity eventCheckinEntity : entities ) {
            list.add( toCheckinResponse( eventCheckinEntity ) );
        }

        return list;
    }

    @Override
    public TimetableItemResponse toTimetableItemResponse(EventTimetableItemEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long eventId = null;
        String title = null;
        String description = null;
        String speaker = null;
        LocalDateTime startAt = null;
        LocalDateTime endAt = null;
        String location = null;
        Integer sortOrder = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        eventId = entity.getEventId();
        title = entity.getTitle();
        description = entity.getDescription();
        speaker = entity.getSpeaker();
        startAt = entity.getStartAt();
        endAt = entity.getEndAt();
        location = entity.getLocation();
        sortOrder = entity.getSortOrder();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        TimetableItemResponse timetableItemResponse = new TimetableItemResponse( id, eventId, title, description, speaker, startAt, endAt, location, sortOrder, createdAt, updatedAt );

        return timetableItemResponse;
    }

    @Override
    public List<TimetableItemResponse> toTimetableItemResponseList(List<EventTimetableItemEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TimetableItemResponse> list = new ArrayList<TimetableItemResponse>( entities.size() );
        for ( EventTimetableItemEntity eventTimetableItemEntity : entities ) {
            list.add( toTimetableItemResponse( eventTimetableItemEntity ) );
        }

        return list;
    }

    @Override
    public InviteTokenResponse toInviteTokenResponse(EventGuestInviteTokenEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long eventId = null;
        String token = null;
        String label = null;
        Integer maxUses = null;
        Integer usedCount = null;
        LocalDateTime expiresAt = null;
        Boolean isActive = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        eventId = entity.getEventId();
        token = entity.getToken();
        label = entity.getLabel();
        maxUses = entity.getMaxUses();
        usedCount = entity.getUsedCount();
        expiresAt = entity.getExpiresAt();
        isActive = entity.getIsActive();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        InviteTokenResponse inviteTokenResponse = new InviteTokenResponse( id, eventId, token, label, maxUses, usedCount, expiresAt, isActive, createdBy, createdAt, updatedAt );

        return inviteTokenResponse;
    }

    @Override
    public List<InviteTokenResponse> toInviteTokenResponseList(List<EventGuestInviteTokenEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<InviteTokenResponse> list = new ArrayList<InviteTokenResponse>( entities.size() );
        for ( EventGuestInviteTokenEntity eventGuestInviteTokenEntity : entities ) {
            list.add( toInviteTokenResponse( eventGuestInviteTokenEntity ) );
        }

        return list;
    }
}
