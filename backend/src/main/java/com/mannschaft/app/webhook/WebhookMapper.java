package com.mannschaft.app.webhook;

import com.mannschaft.app.webhook.entity.ApiKeyEntity;
import com.mannschaft.app.webhook.entity.IncomingWebhookTokenEntity;
import com.mannschaft.app.webhook.entity.WebhookDeliveryLogEntity;
import com.mannschaft.app.webhook.entity.WebhookEndpointEntity;
import com.mannschaft.app.webhook.service.ApiKeyService.ApiKeyResponse;
import com.mannschaft.app.webhook.service.IncomingWebhookService.IncomingWebhookTokenResponse;
import com.mannschaft.app.webhook.service.WebhookDeliveryService.DeliveryLogResponse;
import com.mannschaft.app.webhook.service.WebhookEndpointService.WebhookEndpointResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDate;

/**
 * Webhookドメインのエンティティ → DTO 変換 MapStructマッパー。
 * <p>
 * 注意: eventTypes（購読イベント一覧）はWebhookEndpointEntityに存在しないため
 * ignore指定しており、Service層での別途取得が必要。
 * </p>
 */
@Mapper(componentModel = "spring")
public interface WebhookMapper {

    /**
     * WebhookEndpointEntity を WebhookEndpointResponse に変換する。
     * eventTypes はエンティティに保持されていないため null になる（Service層で補完）。
     * description はエンティティに存在しないため null になる。
     */
    @Mapping(target = "eventTypes", ignore = true)
    @Mapping(target = "description", ignore = true)
    @Mapping(target = "isActive", source = "active")
    WebhookEndpointResponse toEndpointResponse(WebhookEndpointEntity entity);

    /**
     * WebhookDeliveryLogEntity を DeliveryLogResponse に変換する。
     * durationMs はエンティティに保持されていないため null になる。
     * status は deliveryStatus フィールドからマッピングする。
     */
    @Mapping(target = "durationMs", ignore = true)
    @Mapping(target = "status", source = "deliveryStatus")
    @Mapping(target = "statusCode", source = "responseStatus")
    @Mapping(target = "sentAt", source = "createdAt")
    DeliveryLogResponse toDeliveryLogResponse(WebhookDeliveryLogEntity entity);

    /**
     * ApiKeyEntity を ApiKeyResponse に変換する。
     * permissions・description はエンティティに個別フィールドとして保持されていないため null になる。
     * expiresAt は LocalDateTime から LocalDate に変換する。
     */
    @Mapping(target = "permissions", ignore = true)
    @Mapping(target = "description", ignore = true)
    @Mapping(target = "expiresAt",
            expression = "java(entity.getExpiresAt() != null ? entity.getExpiresAt().toLocalDate() : null)")
    ApiKeyResponse toApiKeyResponse(ApiKeyEntity entity);

    /**
     * IncomingWebhookTokenEntity を IncomingWebhookTokenResponse に変換する。
     * description はエンティティに保持されていないため null になる。
     */
    @Mapping(target = "description", ignore = true)
    @Mapping(target = "isActive", source = "active")
    IncomingWebhookTokenResponse toIncomingTokenResponse(IncomingWebhookTokenEntity entity);
}
