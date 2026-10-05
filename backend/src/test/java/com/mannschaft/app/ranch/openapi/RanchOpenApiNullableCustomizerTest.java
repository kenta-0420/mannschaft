package com.mannschaft.app.ranch.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.ranch.dto.HatchResponse;
import com.mannschaft.app.ranch.dto.RanchRecord;
import com.mannschaft.app.ranch.dto.RanchState;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 注釈の反射だけでなく、実converterとOAS3.1 serializerのJSONを検査する。 */
class RanchOpenApiNullableCustomizerTest {
    private final RanchOpenApiNullableCustomizer customizer = new RanchOpenApiNullableCustomizer();

    @Test
    void actualOas31JsonAllowsReferenceNullsAndKeepsReferencedComponentsNonNull() throws Exception {
        var api = resolvedApi();
        customizer.customise(api);
        var schemas = actualJson(api).path("components").path("schemas");
        for (String property : List.of("owner", "dinosaur", "settings", "careBudget", "weekBudget", "assignment")) {
            var nullable = property(schemas, "RanchState", property);
            assertNullBranch(nullable);
            assertThat(nullable.path("anyOf").get(0).path("$ref").asText()).startsWith("#/components/schemas/");
            assertThat(nullable.has("$ref")).isFalse();
        }
        for (String property : List.of("result", "state")) {
            assertNullBranch(property(schemas, "HatchResponse", property));
        }
        assertThat(property(schemas, "HatchResponse", "result").path("anyOf").get(0).path("$ref").asText())
                .isEqualTo("#/components/schemas/HatchResult");
        assertThat(schemas.path("DinosaurSummary").path("type").asText()).isEqualTo("object");
        assertThat(schemas.path("RanchState").has("anyOf")).isFalse();
        assertThat(schemas.path("CareBudget").has("anyOf")).isFalse();
    }

    @Test
    void actualOas31JsonKeepsNullableFormatsEnumsAndAllOtherSchemasUnchanged() throws Exception {
        var api = resolvedApi();
        var before = actualJson(api).path("components").path("schemas");
        customizer.customise(api);
        var after = actualJson(api).path("components").path("schemas");
        for (String property : List.of("speciesKey", "variantKey", "habitat", "speciesCatalogVersion", "name",
                "namedAt", "nextStageXp", "egg")) assertNullBranch(property(after, "DinosaurSummary", property));
        for (String[] target : List.of(new String[]{"RanchState", "policyVersion"},
                new String[]{"AssignmentSummary", "confirmedMethod"}, new String[]{"EggSummary", "hatchedAt"},
                new String[]{"RoomSlotSummary", "inventoryId"}, new String[]{"RanchRecord", "sourceType"},
                new String[]{"RanchRecord", "sourceLink"})) assertNullBranch(property(after, target[0], target[1]));
        assertThat(property(after, "DinosaurSummary", "namedAt").path("anyOf").get(0).path("format").asText())
                .isEqualTo("date-time");
        assertThat(property(after, "RoomSlotSummary", "inventoryId").path("anyOf").get(0).path("format").asText())
                .isEqualTo("uuid");
        assertThat(property(after, "DinosaurSummary", "habitat").path("anyOf").get(0).path("enum"))
                .isEqualTo(property(before, "DinosaurSummary", "habitat").path("enum"));
        for (String[] target : List.of(new String[]{"RanchState", "serverTime"},
                new String[]{"HatchResponse", "kind"}, new String[]{"DinosaurSummary", "id"},
                new String[]{"RanchRecord", "id"})) {
            assertThat(property(after, target[0], target[1])).isEqualTo(property(before, target[0], target[1]));
        }
        for (String schema : List.of("RanchState", "HatchResponse", "DinosaurSummary", "AssignmentSummary",
                "EggSummary", "RoomSlotSummary", "RanchRecord")) {
            assertThat(after.path(schema).path("required")).isEqualTo(before.path(schema).path("required"));
        }
        assertThat(before.path("CareBudget").isMissingNode()).isFalse();
        property(before, "ForeignNullableControl", "value");
        assertThat(after.path("CareBudget")).isEqualTo(before.path("CareBudget"));
        assertThat(after.path("ForeignNullableControl")).isEqualTo(before.path("ForeignNullableControl"));
    }

    @Test
    void repeatedCustomizationHasIdenticalActualJsonAndLeavesOas30Unchanged() throws Exception {
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
        // singleton converterの設定変更をせず、この試験専用の実3.1 converterを使う。
        var converters = new ModelConverters(true);
        var components = new Components();
        for (Class<?> root : List.of(RanchState.class, HatchResponse.class, RanchRecord.class, ForeignNullableControl.class)) {
            converters.readAll(root).forEach(components::addSchemas);
        }
        return new OpenAPI().openapi("3.1.0").components(components);
    }

    private static JsonNode actualJson(OpenAPI api) throws Exception {
        return Json31.mapper().readTree(Json31.mapper().writeValueAsString(api));
    }

    private static JsonNode property(JsonNode schemas, String schema, String name) {
        var property = schemas.path(schema).path("properties").path(name);
        assertThat(property.isMissingNode()).as("実生成property %s.%s", schema, name).isFalse();
        return property;
    }

    private static void assertNullBranch(JsonNode property) {
        assertThat(property.fieldNames()).toIterable().containsExactly("anyOf");
        assertThat(property.path("anyOf").size()).isEqualTo(2);
        // 実serializerがnull schemaを空objectやnullable:trueへ変えていないことも確認する。
        assertThat(property.path("anyOf").get(1).fieldNames()).toIterable().containsExactly("type");
        assertThat(property.path("anyOf").get(1).path("type").asText()).isEqualTo("null");
    }

    record ForeignNullableControl(@io.swagger.v3.oas.annotations.media.Schema(nullable = true) String value) { }
}
