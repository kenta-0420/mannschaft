package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DisclosureFormTemplateValidator} 単体テスト（F09.14 Phase 2-β-2）。
 *
 * <p>設計書 §3 / §6.4 / §6.5 のバリデーション要件を網羅する。</p>
 */
@DisplayName("DisclosureFormTemplateValidator 単体テスト")
class DisclosureFormTemplateValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DisclosureAutoFillService autoFillService;
    private DisclosureFormTemplateValidator validator;

    /**
     * テスト用 AutoFillSource — registry に登録するだけのスタブ。
     */
    private static class StubSource implements AutoFillSource {
        private final String key;

        StubSource(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Object resolve(AutoFillContext context) {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        autoFillService = new DisclosureAutoFillService(List.of(
                new StubSource("organization.name"),
                new StubSource("dwelling_unit.unit_number"),
                new StubSource("property_history.packages")));
        autoFillService.init();
        validator = new DisclosureFormTemplateValidator(objectMapper, autoFillService);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("正常系")
    class Valid {

        @Test
        @DisplayName("最小の有効な schema は素通り")
        void minimal() {
            JsonNode schema = parse("""
                { "sections": [
                  { "id": "s1", "title": "基本", "fields": [
                    { "id": "f1", "label": "物件名", "type": "TEXT" }
                  ]}
                ]}
                """);
            assertThatCode(() -> validator.validate(schema)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("登録済み autoFillFrom キーと autoFillFilter は OK")
        void validAutoFillKey() {
            JsonNode schema = parse("""
                { "sections": [
                  { "id": "s1", "title": "S", "fields": [
                    { "id": "name", "label": "物件名", "type": "TEXT",
                      "autoFillFrom": "organization.name" },
                    { "id": "history", "label": "工事履歴", "type": "AUTO_TABLE",
                      "autoFillFrom": "property_history.packages",
                      "autoFillFilter": { "isDisclosable": true } }
                  ]}
                ]}
                """);
            assertThatCode(() -> validator.validate(schema)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("全 type 値は許容される")
        void allTypesAccepted() {
            String fields = String.join(",\n",
                    """
                    { "id":"f1","label":"L","type":"TEXT" }""",
                    """
                    { "id":"f2","label":"L","type":"NUMBER" }""",
                    """
                    { "id":"f3","label":"L","type":"DATE" }""",
                    """
                    { "id":"f4","label":"L","type":"SELECT" }""",
                    """
                    { "id":"f5","label":"L","type":"MULTISELECT" }""",
                    """
                    { "id":"f6","label":"L","type":"CHECKBOX" }""",
                    """
                    { "id":"f7","label":"L","type":"TEXTAREA" }""",
                    """
                    { "id":"f8","label":"L","type":"AUTO_TABLE" }""",
                    """
                    { "id":"f9","label":"L","type":"AUTO_FIELD" }""");
            JsonNode schema = parse("{ \"sections\":[{\"id\":\"s\",\"title\":\"T\",\"fields\":["
                    + fields + "]}]}");
            assertThatCode(() -> validator.validate(schema)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("異常系")
    class Invalid {

        @Test
        @DisplayName("null schema は DISCLOSURE_004")
        void nullSchema() {
            assertThatThrownBy(() -> validator.validate(null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
        }

        @Test
        @DisplayName("sections 欠落は DISCLOSURE_004")
        void missingSections() {
            JsonNode schema = parse("{ \"foo\": 1 }");
            assertThatThrownBy(() -> validator.validate(schema))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
        }

        @Test
        @DisplayName("section.id / title 欠落はフィールドエラーで報告")
        void sectionMissingFields() {
            JsonNode schema = parse("""
                { "sections": [ { "fields": [] } ] }
                """);
            BusinessException ex = catchBusinessException(() -> validator.validate(schema));
            assertThat(ex.getFieldErrors())
                    .extracting(f -> f.getField())
                    .anyMatch(s -> s.contains(".id"))
                    .anyMatch(s -> s.contains(".title"));
        }

        @Test
        @DisplayName("field.type が許容外なら違反扱い")
        void invalidFieldType() {
            JsonNode schema = parse("""
                { "sections": [ { "id":"s","title":"T","fields":[
                  { "id":"f","label":"L","type":"PASSWORD" }
                ]}]}
                """);
            BusinessException ex = catchBusinessException(() -> validator.validate(schema));
            assertThat(ex.getFieldErrors())
                    .anyMatch(f -> f.getMessage().contains("未知の field.type"));
        }

        @Test
        @DisplayName("未登録 autoFillFrom キーは違反（ホワイトリスト方式）")
        void unknownAutoFillKey() {
            JsonNode schema = parse("""
                { "sections": [ { "id":"s","title":"T","fields":[
                  { "id":"f","label":"L","type":"TEXT",
                    "autoFillFrom":"evil.reflection" }
                ]}]}
                """);
            BusinessException ex = catchBusinessException(() -> validator.validate(schema));
            assertThat(ex.getFieldErrors())
                    .anyMatch(f -> f.getMessage().contains("未知の autoFillFrom"));
        }

        @Test
        @DisplayName("section.id が重複していれば違反")
        void duplicateSectionId() {
            JsonNode schema = parse("""
                { "sections": [
                  { "id":"s","title":"T1","fields":[{ "id":"f1","label":"L","type":"TEXT" }] },
                  { "id":"s","title":"T2","fields":[{ "id":"f2","label":"L","type":"TEXT" }] }
                ]}
                """);
            BusinessException ex = catchBusinessException(() -> validator.validate(schema));
            assertThat(ex.getFieldErrors())
                    .anyMatch(f -> f.getMessage().contains("section.id が重複"));
        }

        @Test
        @DisplayName("field.id が重複していれば違反")
        void duplicateFieldId() {
            JsonNode schema = parse("""
                { "sections": [ { "id":"s","title":"T","fields":[
                  { "id":"f","label":"L1","type":"TEXT" },
                  { "id":"f","label":"L2","type":"TEXT" }
                ]}]}
                """);
            BusinessException ex = catchBusinessException(() -> validator.validate(schema));
            assertThat(ex.getFieldErrors())
                    .anyMatch(f -> f.getMessage().contains("field.id が重複"));
        }

        @Test
        @DisplayName("autoFillFilter がオブジェクト以外なら違反")
        void invalidFilterType() {
            JsonNode schema = parse("""
                { "sections": [ { "id":"s","title":"T","fields":[
                  { "id":"f","label":"L","type":"TEXT",
                    "autoFillFrom":"organization.name",
                    "autoFillFilter": "not-an-object" }
                ]}]}
                """);
            BusinessException ex = catchBusinessException(() -> validator.validate(schema));
            assertThat(ex.getFieldErrors())
                    .anyMatch(f -> f.getMessage().contains("autoFillFilter はオブジェクト"));
        }

        @Test
        @DisplayName("100KB 超のサイズは違反")
        void sizeExceeded() {
            // 巨大な label 文字列を仕込んで 100KB を超えさせる
            String huge = "あ".repeat(40_000); // UTF-8 で約 120KB
            String json = "{ \"sections\": [ { \"id\":\"s\",\"title\":\"T\",\"fields\":["
                    + "{ \"id\":\"f\",\"label\":\"" + huge + "\",\"type\":\"TEXT\" } ] } ] }";
            JsonNode schema = parse(json);
            BusinessException ex = catchBusinessException(() -> validator.validate(schema));
            assertThat(ex.getFieldErrors())
                    .anyMatch(f -> f.getMessage().contains("サイズが上限"));
        }

        @Test
        @DisplayName("ネスト深さが上限超過なら違反")
        void nestTooDeep() {
            // 6 段ネストの object（fields 配列の中の field の中の autoFillFilter の中…の発想ではなく
            // 単純にネスト object を作ってサイズチェックの後に検出される想定）
            // depth=1: root, 2: sections array, 3: section obj, 4: fields array, 5: field obj
            // → さらに autoFillFilter で深く積む
            JsonNode schema = parse("""
                { "sections": [ { "id":"s","title":"T","fields":[
                  { "id":"f","label":"L","type":"TEXT",
                    "autoFillFilter": { "lvl1": { "lvl2": { "lvl3": { "lvl4": { "lvl5": 1 }}}}} }
                ]}]}
                """);
            BusinessException ex = catchBusinessException(() -> validator.validate(schema));
            assertThat(ex.getFieldErrors())
                    .anyMatch(f -> f.getMessage().contains("ネスト"));
        }
    }

    /**
     * BusinessException を捕まえて返す（AssertJ から検査するためのヘルパー）。
     */
    private BusinessException catchBusinessException(Runnable r) {
        try {
            r.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("BusinessException expected but none thrown");
    }
}
