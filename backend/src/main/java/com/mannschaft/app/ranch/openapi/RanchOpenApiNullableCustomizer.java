package com.mannschaft.app.ranch.openapi;

import com.mannschaft.app.ranch.dto.AssignmentSummary;
import com.mannschaft.app.ranch.dto.DinosaurSummary;
import com.mannschaft.app.ranch.dto.EggSummary;
import com.mannschaft.app.ranch.dto.HatchResponse;
import com.mannschaft.app.ranch.dto.RanchRecord;
import com.mannschaft.app.ranch.dto.RanchState;
import com.mannschaft.app.ranch.dto.RoomSlotSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** 牧場の明示nullableだけをOAS3.1のnull分岐へ変換し、参照先自体は変更しない。 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public final class RanchOpenApiNullableCustomizer implements OpenApiCustomizer {
    private static final List<Class<?>> OWNED_RECORDS = List.of(RanchState.class, HatchResponse.class,
            DinosaurSummary.class, AssignmentSummary.class, EggSummary.class, RanchRecord.class,
            RoomSlotSummary.class);

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getOpenapi() == null || !openApi.getOpenapi().startsWith("3.1.")
                || openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) return;
        for (Class<?> record : OWNED_RECORDS) {
            io.swagger.v3.oas.models.media.Schema<?> schema =
                    openApi.getComponents().getSchemas().get(record.getSimpleName());
            if (schema == null || schema.getProperties() == null) continue;
            for (var component : record.getRecordComponents()) {
                Schema annotation = component.getAccessor().getAnnotation(Schema.class);
                if (annotation == null || !annotation.nullable()) continue;
                var original = schema.getProperties().get(component.getName());
                if (original == null || hasNullBranch(original)) continue;
                // 元の$ref/type/enum/formatを別分岐に保つ。$refの兄弟typeではnullを許可できない。
                var nullable = new io.swagger.v3.oas.models.media.Schema<>();
                nullable.addAnyOfItem(original);
                nullable.addAnyOfItem(new io.swagger.v3.oas.models.media.Schema<>().types(Set.of("null")));
                schema.addProperty(component.getName(), nullable);
            }
        }
    }

    private static boolean hasNullBranch(io.swagger.v3.oas.models.media.Schema<?> schema) {
        if (schema.getTypes() != null && schema.getTypes().contains("null")) return true;
        return schema.getAnyOf() != null && schema.getAnyOf().stream().anyMatch(branch ->
                branch.getTypes() != null && branch.getTypes().contains("null"));
    }
}
