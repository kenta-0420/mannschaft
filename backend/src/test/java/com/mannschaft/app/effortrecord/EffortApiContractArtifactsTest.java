package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F06.4.1 PR-1 の API 契約成果物（試練。CMP-261115-2145）。AC-58。
 *
 * <ul>
 *   <li>EFFORT prefix の非重複と {@code GlobalExceptionHandler} への HTTP 登録（骨格で登録済みのため green。
 *       既存番人 {@code ErrorCodeHttpStatusDeclarationGuardTest} は宣言番号と実ステータスの一致だけを見るため、
 *       設計 §6 の割当そのものと prefix の独占を本テストで固定する）。</li>
 *   <li>正規生成物 {@code docs/openapi.json} と FE 生成型 {@code frontend/app/types/generated/index.ts} に個人 API が載り、
 *       query は snake_case、応答は {@code ApiResponse}（{@code data}）/ cursor 一覧（{@code data}+{@code meta}）で包まれること
 *       （実装 PR で OpenAPI と生成型を再生成するまで red）。</li>
 * </ul>
 */
@DisplayName("F06.4.1 API 契約成果物（AC-58）")
class EffortApiContractArtifactsTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path OPENAPI = Path.of("../docs/openapi.json");
    private static final Path GENERATED = Path.of("../frontend/app/types/generated/index.ts");

    @Test
    @DisplayName("AC-58: EFFORT_001〜005 は設計 §6 の HTTP に登録され、EFFORT prefix は EffortErrorCode が独占する")
    void AC58_ErrorCodeの登録とprefix非重複() throws IOException {
        Map<String, HttpStatus> expected = Map.of(
                "EFFORT_001", HttpStatus.NOT_FOUND,
                "EFFORT_002", HttpStatus.CONFLICT,
                "EFFORT_003", HttpStatus.FORBIDDEN,
                "EFFORT_004", HttpStatus.SERVICE_UNAVAILABLE,
                "EFFORT_005", HttpStatus.SERVICE_UNAVAILABLE);
        Set<String> codes = new HashSet<>();
        for (EffortErrorCode code : EffortErrorCode.values()) {
            assertThat(codes.add(code.getCode())).as("コード重複なし: " + code).isTrue();
            assertThat(code.getCode()).isEqualTo(code.name());
            assertThat(GlobalExceptionHandler.resolveStatus(code)).as(code.getCode()).isEqualTo(expected.get(code.getCode()));
        }
        assertThat(codes).isEqualTo(expected.keySet());

        Pattern literal = Pattern.compile("\"EFFORT_\\d{3}\"");
        List<String> declaredElsewhere = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("EffortErrorCode.java") || name.equals("GlobalExceptionHandler.java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = literal.matcher(source);
                if (matcher.find() && source.contains("implements ErrorCode")) {
                    declaredElsewhere.add(file.toString());
                }
            }
        }
        assertThat(declaredElsewhere).as("EFFORT_ コードを他の ErrorCode enum が宣言しない").isEmpty();
    }

    @Test
    @DisplayName("AC-58: openapi.json に個人 API が載り、query は snake_case、応答は data / data+meta で包まれる")
    void AC58_OpenAPIの個人API契約() throws IOException {
        JsonNode document = new ObjectMapper().readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8));
        JsonNode paths = document.path("paths");
        String me = "/api/v1/effort-records/me";
        assertThat(paths.has(me + "/presets")).as("presets").isTrue();
        // definition-snapshots（PR-2）は AC19_AC21_OpenAPIとFE生成型にチーム定義APIが載る で検査する
        assertThat(paths.has(me + "/stats")).as("stats").isTrue();
        assertThat(paths.path(me).has("get") && paths.path(me).has("post")).as("一覧と作成").isTrue();
        JsonNode byId = paths.path(me + "/{id}");
        assertThat(byId.has("get") && byId.has("put") && byId.has("delete")).as("詳細・更新・削除").isTrue();

        assertThat(parameterNames(paths.path(me).path("get")))
                .contains("date_from", "date_to", "definition_uuid", "cursor", "limit")
                .noneMatch(name -> name.matches(".*[A-Z].*"));
        assertThat(parameterNames(paths.path(me + "/stats").path("get")))
                .contains("date_from", "date_to", "definition_uuid", "field_key", "chart", "grain")
                .noneMatch(name -> name.matches(".*[A-Z].*"));
        assertThat(parameterNames(paths.path(me).path("post"))).contains("Idempotency-Key");
        assertThat(parameterNames(byId.path("delete"))).contains("version", "Idempotency-Key");

        JsonNode schemas = document.path("components").path("schemas");
        JsonNode created = resolve(schemas, paths.path(me).path("post").path("responses").path("201")
                .path("content").path("application/json").path("schema"));
        assertThat(created.path("properties").has("data")).as("作成応答は ApiResponse の data で包む").isTrue();
        JsonNode list = resolve(schemas, paths.path(me).path("get").path("responses").path("200")
                .path("content").path("application/json").path("schema"));
        assertThat(list.path("properties").has("data") && list.path("properties").has("meta"))
                .as("一覧は CursorPagedResponse（data+meta）").isTrue();
        JsonNode record = resolve(schemas, created.path("properties").path("data"));
        assertThat(record.path("properties").has("contentRevision") && record.path("properties").has("recordTimezone"))
                .as("DTO は camelCase").isTrue();
    }

    @Test
    @DisplayName("AC-58: FE 生成型に個人 API の path が載っている（openapi-typescript 再生成済み）")
    void AC58_FE生成型の再生成() throws IOException {
        String generated = Files.readString(GENERATED, StandardCharsets.UTF_8);
        assertThat(generated).contains("\"/api/v1/effort-records/me/presets\"")
                .contains("\"/api/v1/effort-records/me/{id}\"")
                .contains("\"/api/v1/effort-records/me/stats\"");
    }

    @Test
    @DisplayName("AC-19/21（PR-2）: openapi.json と FE 生成型に definition-snapshots と template-settings が載り、応答は data で包まれる")
    void AC19_AC21_OpenAPIとFE生成型にチーム定義APIが載る() throws IOException {
        JsonNode document = new ObjectMapper().readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8));
        JsonNode paths = document.path("paths");
        String snapshots = "/api/v1/effort-records/me/definition-snapshots";
        String settings = "/api/v1/teams/{teamId}/effort-records/template-settings/{templateId}";
        assertThat(paths.path(snapshots).has("post")).as("definition-snapshots POST").isTrue();
        assertThat(paths.path(settings).has("get") && paths.path(settings).has("put")).as("template-settings GET/PUT").isTrue();

        JsonNode schemas = document.path("components").path("schemas");
        JsonNode issued = resolve(schemas, paths.path(snapshots).path("post").path("responses").path("200")
                .path("content").path("application/json").path("schema"));
        JsonNode issuedData = resolve(schemas, issued.path("properties").path("data"));
        assertThat(issuedData.path("properties").has("definitionToken") && issuedData.path("properties").has("expiresAt"))
                .as("definition / definitionToken / expiresAt（camelCase・data 包み）").isTrue();
        JsonNode settingsResponse = resolve(schemas, paths.path(settings).path("get").path("responses").path("200")
                .path("content").path("application/json").path("schema"));
        JsonNode settingsData = resolve(schemas, settingsResponse.path("properties").path("data"));
        assertThat(settingsData.path("properties").has("version") && settingsData.path("properties").has("definition"))
                .as("template-settings は {version, definition} を data で包む").isTrue();

        String generated = Files.readString(GENERATED, StandardCharsets.UTF_8);
        assertThat(generated).contains("\"" + snapshots + "\"").contains("\"" + settings + "\"");
    }

    private static List<String> parameterNames(JsonNode operation) {
        List<String> names = new ArrayList<>();
        operation.path("parameters").forEach(p -> names.add(p.path("name").asText()));
        return names;
    }

    private static JsonNode resolve(JsonNode schemas, JsonNode schema) {
        String ref = schema.path("$ref").asText();
        if (ref.startsWith("#/components/schemas/")) {
            return schemas.path(ref.substring("#/components/schemas/".length()));
        }
        return schema;
    }
}
