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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 順番待ち機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface QueueMapper {

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "queueMode", expression = "java(entity.getQueueMode().name())")
    CategoryResponse toCategoryResponse(QueueCategoryEntity entity);

    List<CategoryResponse> toCategoryResponseList(List<QueueCategoryEntity> entities);

    @Mapping(target = "acceptMode", expression = "java(entity.getAcceptMode().name())")
    CounterResponse toCounterResponse(QueueCounterEntity entity);

    List<CounterResponse> toCounterResponseList(List<QueueCounterEntity> entities);

    @Mapping(target = "source", expression = "java(entity.getSource().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    TicketResponse toTicketResponse(QueueTicketEntity entity);

    List<TicketResponse> toTicketResponseList(List<QueueTicketEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    SettingsResponse toSettingsResponse(QueueSettingsEntity entity);

    QrCodeResponse toQrCodeResponse(QueueQrCodeEntity entity);

    List<QrCodeResponse> toQrCodeResponseList(List<QueueQrCodeEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    DailyStatsResponse toDailyStatsResponse(QueueDailyStatsEntity entity);

    List<DailyStatsResponse> toDailyStatsResponseList(List<QueueDailyStatsEntity> entities);
}
