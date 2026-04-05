package com.mannschaft.app.ticket;

import com.mannschaft.app.ticket.dto.ConsumptionResponse;
import com.mannschaft.app.ticket.dto.TicketBookResponse;
import com.mannschaft.app.ticket.dto.TicketProductResponse;
import com.mannschaft.app.ticket.entity.TicketBookEntity;
import com.mannschaft.app.ticket.entity.TicketConsumptionEntity;
import com.mannschaft.app.ticket.entity.TicketProductEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 回数券機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface TicketMapper {

    @Mapping(target = "priceExcludingTax", expression = "java(entity.getPriceExcludingTax())")
    TicketProductResponse toProductResponse(TicketProductEntity entity);

    List<TicketProductResponse> toProductResponseList(List<TicketProductEntity> entities);

    @Mapping(target = "productName", ignore = true)
    @Mapping(target = "daysUntilExpiry", expression = "java(calculateDaysUntilExpiry(entity.getExpiresAt()))")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    TicketBookResponse toBookResponse(TicketBookEntity entity);

    List<TicketBookResponse> toBookResponseList(List<TicketBookEntity> entities);

    @Mapping(target = "isVoided", source = "isVoided")
    ConsumptionResponse toConsumptionResponse(TicketConsumptionEntity entity);

    List<ConsumptionResponse> toConsumptionResponseList(List<TicketConsumptionEntity> entities);

    /**
     * 有効期限までの残日数を算出する。
     *
     * @param expiresAt 有効期限
     * @return 残日数（null = 無期限）
     */
    default Long calculateDaysUntilExpiry(LocalDateTime expiresAt) {
        if (expiresAt == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(LocalDateTime.now(), expiresAt);
        return Math.max(days, 0);
    }
}
