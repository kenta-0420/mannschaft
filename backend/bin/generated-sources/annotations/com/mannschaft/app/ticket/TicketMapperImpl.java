package com.mannschaft.app.ticket;

import com.mannschaft.app.ticket.dto.ConsumptionResponse;
import com.mannschaft.app.ticket.dto.TicketBookResponse;
import com.mannschaft.app.ticket.dto.TicketProductResponse;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.entity.TicketConsumptionEntity;
import com.mannschaft.app.ticket.entity.TicketProductEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:34+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class TicketMapperImpl implements TicketMapper {

    @Override
    public TicketProductResponse toProductResponse(TicketProductEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String name = null;
        String description = null;
        Integer totalTickets = null;
        Integer price = null;
        BigDecimal taxRate = null;
        Integer validityDays = null;
        Boolean isOnlinePurchasable = null;
        String stripeProductId = null;
        String stripePriceId = null;
        String imageUrl = null;
        Boolean isActive = null;
        Integer sortOrder = null;
        LocalDateTime deletedAt = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        name = entity.getName();
        description = entity.getDescription();
        totalTickets = entity.getTotalTickets();
        price = entity.getPrice();
        taxRate = entity.getTaxRate();
        validityDays = entity.getValidityDays();
        isOnlinePurchasable = entity.getIsOnlinePurchasable();
        stripeProductId = entity.getStripeProductId();
        stripePriceId = entity.getStripePriceId();
        imageUrl = entity.getImageUrl();
        isActive = entity.getIsActive();
        sortOrder = entity.getSortOrder();
        deletedAt = entity.getDeletedAt();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        Integer priceExcludingTax = entity.getPriceExcludingTax();

        TicketProductResponse ticketProductResponse = new TicketProductResponse( id, name, description, totalTickets, price, priceExcludingTax, taxRate, validityDays, isOnlinePurchasable, stripeProductId, stripePriceId, imageUrl, isActive, sortOrder, deletedAt, createdAt, updatedAt );

        return ticketProductResponse;
    }

    @Override
    public List<TicketProductResponse> toProductResponseList(List<TicketProductEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TicketProductResponse> list = new ArrayList<TicketProductResponse>( entities.size() );
        for ( TicketProductEntity ticketProductEntity : entities ) {
            list.add( toProductResponse( ticketProductEntity ) );
        }

        return list;
    }

    @Override
    public TicketBookResponse toBookResponse(TicketBookEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Integer totalTickets = null;
        Integer usedTickets = null;
        Integer remainingTickets = null;
        LocalDateTime purchasedAt = null;
        LocalDateTime expiresAt = null;
        String note = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        totalTickets = entity.getTotalTickets();
        usedTickets = entity.getUsedTickets();
        remainingTickets = entity.getRemainingTickets();
        purchasedAt = entity.getPurchasedAt();
        expiresAt = entity.getExpiresAt();
        note = entity.getNote();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String productName = null;
        Long daysUntilExpiry = calculateDaysUntilExpiry(entity.getExpiresAt());
        String status = entity.getStatus().name();

        TicketBookResponse ticketBookResponse = new TicketBookResponse( id, productName, totalTickets, usedTickets, remainingTickets, status, purchasedAt, expiresAt, daysUntilExpiry, note, createdAt, updatedAt );

        return ticketBookResponse;
    }

    @Override
    public List<TicketBookResponse> toBookResponseList(List<TicketBookEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TicketBookResponse> list = new ArrayList<TicketBookResponse>( entities.size() );
        for ( TicketBookEntity ticketBookEntity : entities ) {
            list.add( toBookResponse( ticketBookEntity ) );
        }

        return list;
    }

    @Override
    public ConsumptionResponse toConsumptionResponse(TicketConsumptionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Boolean isVoided = null;
        Long id = null;
        Long bookId = null;
        LocalDateTime consumedAt = null;
        String note = null;
        LocalDateTime voidedAt = null;

        isVoided = entity.getIsVoided();
        id = entity.getId();
        bookId = entity.getBookId();
        consumedAt = entity.getConsumedAt();
        note = entity.getNote();
        voidedAt = entity.getVoidedAt();

        ConsumptionResponse consumptionResponse = new ConsumptionResponse( id, bookId, consumedAt, note, isVoided, voidedAt );

        return consumptionResponse;
    }

    @Override
    public List<ConsumptionResponse> toConsumptionResponseList(List<TicketConsumptionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ConsumptionResponse> list = new ArrayList<ConsumptionResponse>( entities.size() );
        for ( TicketConsumptionEntity ticketConsumptionEntity : entities ) {
            list.add( toConsumptionResponse( ticketConsumptionEntity ) );
        }

        return list;
    }
}
