package com.mannschaft.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 実 Swagger 3.1 serializer で enum/ref を含む LOCKED の全 null を検証する。 */
class AnnouncementPreviewSchemaTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void LOCKEDの明示nullとFULLの参照型を同時に許容する() throws Exception {
        Schema response = new Schema().type("object");
        Map<String, Schema> fields = new LinkedHashMap<>();
        fields.put("scopeType", new Schema().type("string")._enum(List.of("TEAM", "ORGANIZATION", "PERSONAL")));
        fields.put("sourceType", new Schema().type("string")._enum(List.of("BLOG_POST", "BULLETIN_THREAD", "TODO")));
        fields.put("sourceId", new Schema().type("integer").format("int64"));
        fields.put("sourceUrl", new Schema().type("string"));
        fields.put("blogPost", new Schema().$ref("#/components/schemas/BlogPostResponse"));
        fields.put("bulletinThread", new Schema().$ref("#/components/schemas/ThreadResponse"));
        response.setProperties(fields);
        response.setRequired(List.of("scopeType", "sourceType", "sourceId", "sourceUrl", "blogPost", "bulletinThread"));
        OpenAPI api = new OpenAPI().openapi("3.1.0").components(new Components().addSchemas("AnnouncementPreviewResponse", response));
        new OpenApiConfig().announcementPreviewSchemaCustomizer().customise(api);
        new OpenApiConfig().announcementPreviewSchemaCustomizer().customise(api);
        JsonNode schema = Json31.mapper().readTree(Json31.mapper().writeValueAsString(api))
                .path("components").path("schemas").path("AnnouncementPreviewResponse");
        JsonNode locked = Json31.mapper().readTree("{\"scopeType\":\"TEAM\",\"sourceType\":null,\"sourceId\":null,\"sourceUrl\":null,\"blogPost\":null,\"bulletinThread\":null}");
        for (String field : List.of("sourceType", "sourceId", "sourceUrl", "blogPost", "bulletinThread")) {
            assertThat(locked.has(field) && locked.get(field).isNull()).isTrue();
            JsonNode branches = schema.path("properties").path(field).path("anyOf");
            assertThat(branches.size()).isEqualTo(2);
            // null の分岐を enum/$ref と分離し、nullable sibling だけの偽陽性を防ぐ。
            assertThat(branches.get(1).path("type").asText()).isEqualTo("null");
            assertThat(branches.get(1).has("enum") || branches.get(1).has("$ref")).isFalse();
        }
        assertThat(schema.path("properties").path("blogPost").path("anyOf").get(0).path("$ref").asText())
                .isEqualTo("#/components/schemas/BlogPostResponse");
        assertThat(schema.path("properties").path("bulletinThread").path("anyOf").get(0).path("$ref").asText())
                .isEqualTo("#/components/schemas/ThreadResponse");
        assertThat(schema.path("properties").path("sourceType").path("anyOf").get(0).path("enum").toString())
                .isEqualTo("[\"BLOG_POST\",\"BULLETIN_THREAD\"]");
        assertThat(schema.path("required").size()).isEqualTo(6);
    }
}
