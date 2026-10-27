package com.mannschaft.app.common.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 正規生成された契約で、一般エラーと同名確認専用エラーの詳細型が衝突しないことを固定する。 */
@DisplayName("OpenAPIの一般エラーと同名確認エラーは独立した詳細契約を持つ")
class ErrorResponseOpenApiContractTest {
    private static JsonNode document;
    private static JsonNode schemas;

    @BeforeAll
    static void 正規生成物を読む() throws Exception {
        document = new ObjectMapper().readTree(Files.readString(Path.of("../docs/openapi.json")));
        schemas = document.path("components").path("schemas");
        assertThat(schemas.isMissingNode()).as("正規OpenAPIのschemasがある").isFalse();
    }

    @Test
    void 同名確認は候補一覧とfingerprintの詳細を保持する() {
        JsonNode detail = resolve(schemas.path("DuplicateNameConfirmationErrorResponse")
                .path("properties").path("error"));
        assertThat(detail.path("properties").has("details"))
                .as("同名確認error.detailsは一般ErrorDetailの解決順で失われない").isTrue();
        JsonNode confirmation = resolve(detail.path("properties").path("details"));
        assertThat(confirmation.path("properties").has("fingerprint")).isTrue();
        assertThat(schemas.has("DuplicateNameCandidateView"))
                .as("既存FEが参照する候補型を保持する").isTrue();
    }

    @Test
    void 申請APIの一般エラーは同名確認の詳細と別schemaを参照する() {
        JsonNode generalError = schemas.path("ErrorResponse").path("properties").path("error");
        JsonNode duplicateError = schemas.path("DuplicateNameConfirmationErrorResponse")
                .path("properties").path("error");
        assertThat(generalError.path("$ref").asText()).as("異なるJava型の詳細schemaを共有しない")
                .isNotEqualTo(duplicateError.path("$ref").asText());
        JsonNode fields = resolve(generalError).path("properties");
        assertThat(fields.has("code") && fields.has("message") && fields.has("fieldErrors")).isTrue();
        assertThat(fields.has("details")).isFalse();
        assertThat(document.at("/paths/~1api~1v1~1me~1friend-request-source-teams/get/responses/400/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ErrorResponse");
        assertThat(document.at("/paths/~1api~1v1~1teams~1{teamId}~1friends~1incoming/get/responses/403/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ErrorResponse");
    }

    private static JsonNode resolve(JsonNode reference) {
        String ref = reference.path("$ref").asText();
        assertThat(ref).as("schema参照がある").startsWith("#/components/schemas/");
        return schemas.path(ref.substring("#/components/schemas/".length()));
    }
}
