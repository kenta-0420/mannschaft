package com.mannschaft.app.loyalty.openapi;

import com.mannschaft.app.loyalty.dto.AccountDetail;
import com.mannschaft.app.loyalty.dto.AwardRequestResponse;
import com.mannschaft.app.loyalty.dto.CreateRewardRequest;
import com.mannschaft.app.loyalty.dto.OperationCatalog;
import com.mannschaft.app.loyalty.dto.ProgramSettings;
import com.mannschaft.app.loyalty.dto.RewardProgress;
import com.mannschaft.app.loyalty.dto.RewardResponse;
import com.mannschaft.app.loyalty.dto.SelfAwardResult;
import com.mannschaft.app.loyalty.dto.TransactionResponse;
import com.mannschaft.app.loyalty.dto.UpdateProgramRequest;
import com.mannschaft.app.loyalty.dto.UpdateRewardRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** チームポイントの明示nullableだけをOAS3.1の独立したnull分岐にする。 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public final class LoyaltyOpenApiNullableCustomizer implements OpenApiCustomizer {

    private static final List<Class<?>> OWNED_RECORDS = List.of(AccountDetail.class,
            AwardRequestResponse.class, CreateRewardRequest.class, OperationCatalog.class,
            ProgramSettings.class, RewardProgress.class, RewardResponse.class,
            SelfAwardResult.class, TransactionResponse.class, UpdateProgramRequest.class,
            UpdateRewardRequest.class);

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getOpenapi() == null || !openApi.getOpenapi().startsWith("3.1.")
                || openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        for (Class<?> record : OWNED_RECORDS) {
            Schema recordSchema = record.getAnnotation(Schema.class);
            io.swagger.v3.oas.models.media.Schema<?> schema =
                    openApi.getComponents().getSchemas().get(recordSchema.name());
            if (schema == null || schema.getProperties() == null) continue;
            for (var component : record.getRecordComponents()) {
                Schema annotation = component.getAccessor().getAnnotation(Schema.class);
                if (annotation == null || !annotation.nullable()) continue;
                var original = schema.getProperties().get(component.getName());
                if (original == null || hasNullBranch(original)) continue;
                // $refとenumの制約を元分岐に保ち、nullをそれらとANDしない。
                var nullable = new io.swagger.v3.oas.models.media.Schema<>();
                nullable.addAnyOfItem(original);
                nullable.addAnyOfItem(new io.swagger.v3.oas.models.media.Schema<>()
                        .types(Set.of("null")));
                schema.addProperty(component.getName(), nullable);
            }
        }
    }

    private static boolean hasNullBranch(io.swagger.v3.oas.models.media.Schema<?> schema) {
        return schema.getAnyOf() != null && schema.getAnyOf().stream().anyMatch(branch ->
                Set.of("null").equals(branch.getTypes()) && branch.get$ref() == null
                        && branch.getEnum() == null);
    }
}
