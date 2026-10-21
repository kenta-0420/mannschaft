package com.mannschaft.app.loyalty.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 実converterとserializerでnull分岐、元制約、所有範囲、冪等性を検査する。 */
class LoyaltyOpenApiNullableCustomizerTest {

    private final LoyaltyOpenApiNullableCustomizer customizer =
            new LoyaltyOpenApiNullableCustomizer();

    @Test
    void 実生成の参照とenumとformatを独立null分岐で保持する() throws Exception {
        var api = resolvedApi();
        var before = actualJson(api);
        customizer.customise(api);
        var after = actualJson(api);
        for (String[] target : List.of(
                new String[]{"LoyaltyAccountDetail", "accountId"},
                new String[]{"LoyaltyAwardRequestResponse", "reason"},
                new String[]{"LoyaltyAwardRequestResponse", "closedAt"},
                new String[]{"LoyaltyOperationCatalog", "rule"},
                new String[]{"LoyaltySelfAwardResult", "transaction"},
                new String[]{"LoyaltySelfAwardResult", "request"},
                new String[]{"LoyaltyTransactionResponse", "awardOn"},
                new String[]{"LoyaltyCreateRewardRequest", "description"},
                new String[]{"LoyaltyUpdateProgramRequest", "expectedVersion"})) {
            var value = property(after, target[0], target[1]);
            assertThat(value.fieldNames()).toIterable().containsExactly("anyOf");
            assertThat(value.path("anyOf").size()).isEqualTo(2);
            assertThat(value.path("anyOf").get(0))
                    .isEqualTo(property(before, target[0], target[1]));
            assertThat(value.path("anyOf").get(1).toString()).isEqualTo("{\"type\":\"null\"}");
        }
    }

    @Test
    void 非対象schemaと必須配列と参照先自体を変更しない() throws Exception {
        var api = resolvedApi();
        var before = actualJson(api);
        customizer.customise(api);
        var after = actualJson(api);
        assertThat(after.path("ForeignNullableControl")).isEqualTo(before.path("ForeignNullableControl"));
        assertThat(property(after, "LoyaltyAccountDetail", "teamId"))
                .isEqualTo(property(before, "LoyaltyAccountDetail", "teamId"));
        for (String name : List.of("LoyaltyAccountDetail", "LoyaltyAwardRequestResponse",
                "LoyaltyCreateRewardRequest", "LoyaltyOperationCatalog", "LoyaltyProgramSettings",
                "LoyaltyRewardProgress", "LoyaltyRewardResponse", "LoyaltySelfAwardResult",
                "LoyaltyTransactionResponse", "LoyaltyUpdateProgramRequest", "LoyaltyUpdateRewardRequest")) {
            assertThat(after.path(name).path("required")).isEqualTo(before.path(name).path("required"));
            assertThat(after.path(name).has("anyOf")).isFalse();
        }
    }

    @Test
    void 二回適用は同一JSONでOAS30は変更しない() throws Exception {
        var api = resolvedApi();
        customizer.customise(api);
        String first = Json31.mapper().writeValueAsString(api);
        customizer.customise(api);
        assertThat(Json31.mapper().writeValueAsString(api)).isEqualTo(first);
        var older = resolvedApi().openapi("3.0.3");
        String before = Json.mapper().writeValueAsString(older);
        customizer.customise(older);
        assertThat(Json.mapper().writeValueAsString(older)).isEqualTo(before);
    }

    private static OpenAPI resolvedApi() {
        var converters = new ModelConverters(true);
        var components = new Components();
        for (Class<?> root : List.of(AccountDetail.class, AwardRequestResponse.class,
                CreateRewardRequest.class, OperationCatalog.class, ProgramSettings.class,
                RewardProgress.class, RewardResponse.class, SelfAwardResult.class,
                TransactionResponse.class, UpdateProgramRequest.class, UpdateRewardRequest.class,
                ForeignNullableControl.class)) {
            converters.readAll(root).forEach(components::addSchemas);
        }
        return new OpenAPI().openapi("3.1.0").components(components);
    }

    private static JsonNode actualJson(OpenAPI api) throws Exception {
        return Json31.mapper().readTree(Json31.mapper().writeValueAsString(api))
                .path("components").path("schemas");
    }

    private static JsonNode property(JsonNode schemas, String schema, String name) {
        var property = schemas.path(schema).path("properties").path(name);
        assertThat(property.isMissingNode()).as("実生成property %s.%s", schema, name).isFalse();
        return property;
    }

    record ForeignNullableControl(@Schema(nullable = true) String value) { }
}
