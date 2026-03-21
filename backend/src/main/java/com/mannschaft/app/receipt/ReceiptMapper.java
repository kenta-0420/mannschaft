package com.mannschaft.app.receipt;

import com.mannschaft.app.receipt.dto.IssuerSettingsResponse;
import com.mannschaft.app.receipt.dto.PresetResponse;
import com.mannschaft.app.receipt.dto.QueueItemResponse;
import com.mannschaft.app.receipt.dto.ReceiptSummaryResponse;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.entity.ReceiptPresetEntity;
import com.mannschaft.app.receipt.entity.ReceiptQueueEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 領収書機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ReceiptMapper {

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "defaultSealVariant",
            expression = "java(entity.getDefaultSealVariant() != null ? entity.getDefaultSealVariant().name() : null)")
    IssuerSettingsResponse toIssuerSettingsResponse(ReceiptIssuerSettingsEntity entity);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    PresetResponse toPresetResponse(ReceiptPresetEntity entity);

    List<PresetResponse> toPresetResponseList(List<ReceiptPresetEntity> entities);

    @Mapping(target = "scopeType", expression = "java(entity.getScopeType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    QueueItemResponse toQueueItemResponse(ReceiptQueueEntity entity);

    List<QueueItemResponse> toQueueItemResponseList(List<ReceiptQueueEntity> entities);

    @Mapping(target = "isVoided", expression = "java(entity.isVoided())")
    @Mapping(target = "sealStamped", expression = "java(entity.getSealStampLogId() != null)")
    ReceiptSummaryResponse toReceiptSummaryResponse(ReceiptEntity entity);

    List<ReceiptSummaryResponse> toReceiptSummaryResponseList(List<ReceiptEntity> entities);
}
