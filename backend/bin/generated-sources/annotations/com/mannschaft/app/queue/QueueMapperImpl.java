package com.mannschaft.app.queue;

import com.mannschaft.app.queue.dto.CategoryResponse;
import com.mannschaft.app.queue.dto.CounterResponse;
import com.mannschaft.app.queue.dto.DailyStatsResponse;
import com.mannschaft.app.queue.dto.QrCodeResponse;
import com.mannschaft.app.queue.dto.SettingsResponse;
import com.mannschaft.app.queue.dto.TicketResponse;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueDailyStatsEntity;
import com.mannschaft.app.queue.entity.QueueQrCodeEntity;
import com.mannschaft.app.queue.entity.QueueSettingsEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:12+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class QueueMapperImpl implements QueueMapper {

    @Override
    public CategoryResponse toCategoryResponse(QueueCategoryEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        String name = null;
        String prefixChar = null;
        Short maxQueueSize = null;
        Short displayOrder = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        name = entity.getName();
        prefixChar = entity.getPrefixChar();
        maxQueueSize = entity.getMaxQueueSize();
        displayOrder = entity.getDisplayOrder();
        createdAt = entity.getCreatedAt();

        String scopeType = entity.getScopeType().name();
        String queueMode = entity.getQueueMode().name();

        CategoryResponse categoryResponse = new CategoryResponse( id, scopeType, scopeId, name, queueMode, prefixChar, maxQueueSize, displayOrder, createdAt );

        return categoryResponse;
    }

    @Override
    public List<CategoryResponse> toCategoryResponseList(List<QueueCategoryEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CategoryResponse> list = new ArrayList<CategoryResponse>( entities.size() );
        for ( QueueCategoryEntity queueCategoryEntity : entities ) {
            list.add( toCategoryResponse( queueCategoryEntity ) );
        }

        return list;
    }

    @Override
    public CounterResponse toCounterResponse(QueueCounterEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long categoryId = null;
        String name = null;
        String description = null;
        Short avgServiceMinutes = null;
        Boolean avgServiceMinutesManual = null;
        Short maxQueueSize = null;
        Boolean isActive = null;
        Boolean isAccepting = null;
        LocalTime operatingTimeFrom = null;
        LocalTime operatingTimeTo = null;
        Short displayOrder = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        categoryId = entity.getCategoryId();
        name = entity.getName();
        description = entity.getDescription();
        avgServiceMinutes = entity.getAvgServiceMinutes();
        avgServiceMinutesManual = entity.getAvgServiceMinutesManual();
        maxQueueSize = entity.getMaxQueueSize();
        isActive = entity.getIsActive();
        isAccepting = entity.getIsAccepting();
        operatingTimeFrom = entity.getOperatingTimeFrom();
        operatingTimeTo = entity.getOperatingTimeTo();
        displayOrder = entity.getDisplayOrder();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();

        String acceptMode = entity.getAcceptMode().name();

        CounterResponse counterResponse = new CounterResponse( id, categoryId, name, description, acceptMode, avgServiceMinutes, avgServiceMinutesManual, maxQueueSize, isActive, isAccepting, operatingTimeFrom, operatingTimeTo, displayOrder, createdBy, createdAt );

        return counterResponse;
    }

    @Override
    public List<CounterResponse> toCounterResponseList(List<QueueCounterEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CounterResponse> list = new ArrayList<CounterResponse>( entities.size() );
        for ( QueueCounterEntity queueCounterEntity : entities ) {
            list.add( toCounterResponse( queueCounterEntity ) );
        }

        return list;
    }

    @Override
    public TicketResponse toTicketResponse(QueueTicketEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long categoryId = null;
        Long counterId = null;
        String ticketNumber = null;
        Long userId = null;
        String guestName = null;
        Short partySize = null;
        Integer position = null;
        Short estimatedWaitMinutes = null;
        LocalDateTime calledAt = null;
        LocalDateTime servingAt = null;
        LocalDateTime completedAt = null;
        LocalDateTime cancelledAt = null;
        LocalDateTime noShowAt = null;
        LocalDateTime holdUntil = null;
        Boolean holdUsed = null;
        Short actualServiceMinutes = null;
        String note = null;
        Long previousTicketId = null;
        LocalDate issuedDate = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        categoryId = entity.getCategoryId();
        counterId = entity.getCounterId();
        ticketNumber = entity.getTicketNumber();
        userId = entity.getUserId();
        guestName = entity.getGuestName();
        partySize = entity.getPartySize();
        position = entity.getPosition();
        estimatedWaitMinutes = entity.getEstimatedWaitMinutes();
        calledAt = entity.getCalledAt();
        servingAt = entity.getServingAt();
        completedAt = entity.getCompletedAt();
        cancelledAt = entity.getCancelledAt();
        noShowAt = entity.getNoShowAt();
        holdUntil = entity.getHoldUntil();
        holdUsed = entity.getHoldUsed();
        actualServiceMinutes = entity.getActualServiceMinutes();
        note = entity.getNote();
        previousTicketId = entity.getPreviousTicketId();
        issuedDate = entity.getIssuedDate();
        createdAt = entity.getCreatedAt();

        String source = entity.getSource().name();
        String status = entity.getStatus().name();

        TicketResponse ticketResponse = new TicketResponse( id, categoryId, counterId, ticketNumber, userId, guestName, partySize, source, status, position, estimatedWaitMinutes, calledAt, servingAt, completedAt, cancelledAt, noShowAt, holdUntil, holdUsed, actualServiceMinutes, note, previousTicketId, issuedDate, createdAt );

        return ticketResponse;
    }

    @Override
    public List<TicketResponse> toTicketResponseList(List<QueueTicketEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TicketResponse> list = new ArrayList<TicketResponse>( entities.size() );
        for ( QueueTicketEntity queueTicketEntity : entities ) {
            list.add( toTicketResponse( queueTicketEntity ) );
        }

        return list;
    }

    @Override
    public SettingsResponse toSettingsResponse(QueueSettingsEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        Short noShowTimeoutMinutes = null;
        Boolean noShowPenaltyEnabled = null;
        Short noShowPenaltyThreshold = null;
        Short noShowPenaltyDays = null;
        Short maxActiveTicketsPerUser = null;
        Boolean allowGuestQueue = null;
        Short almostReadyThreshold = null;
        Short holdExtensionMinutes = null;
        Boolean autoAdjustServiceMinutes = null;
        Boolean displayBoardPublic = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        noShowTimeoutMinutes = entity.getNoShowTimeoutMinutes();
        noShowPenaltyEnabled = entity.getNoShowPenaltyEnabled();
        noShowPenaltyThreshold = entity.getNoShowPenaltyThreshold();
        noShowPenaltyDays = entity.getNoShowPenaltyDays();
        maxActiveTicketsPerUser = entity.getMaxActiveTicketsPerUser();
        allowGuestQueue = entity.getAllowGuestQueue();
        almostReadyThreshold = entity.getAlmostReadyThreshold();
        holdExtensionMinutes = entity.getHoldExtensionMinutes();
        autoAdjustServiceMinutes = entity.getAutoAdjustServiceMinutes();
        displayBoardPublic = entity.getDisplayBoardPublic();

        String scopeType = entity.getScopeType().name();

        SettingsResponse settingsResponse = new SettingsResponse( id, scopeType, scopeId, noShowTimeoutMinutes, noShowPenaltyEnabled, noShowPenaltyThreshold, noShowPenaltyDays, maxActiveTicketsPerUser, allowGuestQueue, almostReadyThreshold, holdExtensionMinutes, autoAdjustServiceMinutes, displayBoardPublic );

        return settingsResponse;
    }

    @Override
    public QrCodeResponse toQrCodeResponse(QueueQrCodeEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long categoryId = null;
        Long counterId = null;
        String qrToken = null;
        Boolean isActive = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        categoryId = entity.getCategoryId();
        counterId = entity.getCounterId();
        qrToken = entity.getQrToken();
        isActive = entity.getIsActive();
        createdAt = entity.getCreatedAt();

        QrCodeResponse qrCodeResponse = new QrCodeResponse( id, categoryId, counterId, qrToken, isActive, createdAt );

        return qrCodeResponse;
    }

    @Override
    public List<QrCodeResponse> toQrCodeResponseList(List<QueueQrCodeEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<QrCodeResponse> list = new ArrayList<QrCodeResponse>( entities.size() );
        for ( QueueQrCodeEntity queueQrCodeEntity : entities ) {
            list.add( toQrCodeResponse( queueQrCodeEntity ) );
        }

        return list;
    }

    @Override
    public DailyStatsResponse toDailyStatsResponse(QueueDailyStatsEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scopeId = null;
        Long counterId = null;
        LocalDate statDate = null;
        Short totalTickets = null;
        Short completedCount = null;
        Short cancelledCount = null;
        Short noShowCount = null;
        BigDecimal avgWaitMinutes = null;
        BigDecimal avgServiceMinutes = null;
        Short peakHour = null;
        Short qrCount = null;
        Short onlineCount = null;

        id = entity.getId();
        scopeId = entity.getScopeId();
        counterId = entity.getCounterId();
        statDate = entity.getStatDate();
        totalTickets = entity.getTotalTickets();
        completedCount = entity.getCompletedCount();
        cancelledCount = entity.getCancelledCount();
        noShowCount = entity.getNoShowCount();
        avgWaitMinutes = entity.getAvgWaitMinutes();
        avgServiceMinutes = entity.getAvgServiceMinutes();
        peakHour = entity.getPeakHour();
        qrCount = entity.getQrCount();
        onlineCount = entity.getOnlineCount();

        String scopeType = entity.getScopeType().name();

        DailyStatsResponse dailyStatsResponse = new DailyStatsResponse( id, scopeType, scopeId, counterId, statDate, totalTickets, completedCount, cancelledCount, noShowCount, avgWaitMinutes, avgServiceMinutes, peakHour, qrCount, onlineCount );

        return dailyStatsResponse;
    }

    @Override
    public List<DailyStatsResponse> toDailyStatsResponseList(List<QueueDailyStatsEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<DailyStatsResponse> list = new ArrayList<DailyStatsResponse>( entities.size() );
        for ( QueueDailyStatsEntity queueDailyStatsEntity : entities ) {
            list.add( toDailyStatsResponse( queueDailyStatsEntity ) );
        }

        return list;
    }
}
