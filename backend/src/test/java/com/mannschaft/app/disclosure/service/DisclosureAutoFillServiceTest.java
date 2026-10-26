package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DisclosureAutoFillService} 単体テスト（F09.14 Phase 2-β-2）。
 *
 * <p>本物の {@link AutoFillSource} を使うとリポジトリ依存で重くなるため、
 * 純粋なスタブ Source を List で渡して挙動を検証する。
 * 実 Source の挙動はそれぞれ統合テスト側で別途確認する。</p>
 */
@DisplayName("DisclosureAutoFillService 単体テスト")
class DisclosureAutoFillServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * テスト用スタブ Source — context をそのまま返却するだけ。
     */
    private static class StubSource implements AutoFillSource {
        private final String key;
        private final Object value;
        private AutoFillContext lastContext;

        StubSource(String key, Object value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Object resolve(AutoFillContext context) {
            this.lastContext = context;
            return value;
        }
    }

    private DisclosureAutoFillService newService(AutoFillSource... sources) {
        DisclosureAutoFillService service = new DisclosureAutoFillService(List.of(sources));
        service.init();
        return service;
    }

    @Nested
    @DisplayName("init / registry")
    class Init {

        @Test
        @DisplayName("登録 Source 数だけ registry が構築され、registeredKeys で取得できる")
        void registryBuilt() {
            DisclosureAutoFillService service = newService(
                    new StubSource("organization.name", "Acme"),
                    new StubSource("dwelling_unit.unit_number", "301"));
            assertThat(service.registeredKeys())
                    .containsExactlyInAnyOrder("organization.name", "dwelling_unit.unit_number");
        }

        @Test
        @DisplayName("同一キーが2件登録された場合は IllegalStateException")
        void duplicateKey() {
            DisclosureAutoFillService service = new DisclosureAutoFillService(List.of(
                    new StubSource("organization.name", "A"),
                    new StubSource("organization.name", "B")));
            assertThatThrownBy(service::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate AutoFillSource key");
        }
    }

    @Nested
    @DisplayName("resolve(sourceKey, context)")
    class Resolve {

        @Test
        @DisplayName("登録済みキーは Source の戻り値をそのまま返す")
        void resolveKnownKey() {
            DisclosureAutoFillService service = newService(
                    new StubSource("organization.name", "サンプルマンション"));
            Object value = service.resolve("organization.name",
                    AutoFillContext.minimal("ORGANIZATION", 1L));
            assertThat(value).isEqualTo("サンプルマンション");
        }

        @Test
        @DisplayName("未登録キーは IllegalArgumentException でブロックされる（ホワイトリスト方式）")
        void resolveUnknownKey() {
            DisclosureAutoFillService service = newService(
                    new StubSource("organization.name", "X"));
            assertThatThrownBy(() -> service.resolve("evil.reflection",
                    AutoFillContext.minimal("ORGANIZATION", 1L)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown autoFillFrom key");
        }

        @Test
        @DisplayName("Source が例外を投げても呼び出し側には null を返し、サービスは継続")
        void resolveSourceThrows() {
            DisclosureAutoFillService service = newService(new AutoFillSource() {
                @Override
                public String key() {
                    return "organization.name";
                }

                @Override
                public Object resolve(AutoFillContext context) {
                    throw new RuntimeException("DB down");
                }
            });
            Object value = service.resolve("organization.name",
                    AutoFillContext.minimal("ORGANIZATION", 1L));
            assertThat(value).isNull();
        }
    }

    @Nested
    @DisplayName("autoFillAll(formSchema, context)")
    class AutoFillAll {

        @Test
        @DisplayName("schema に列挙された全 autoFillFrom フィールドの値を Map で返す")
        void resolvesAllFields() throws Exception {
            DisclosureAutoFillService service = newService(
                    new StubSource("organization.name", "Acme"),
                    new StubSource("dwelling_unit.unit_number", "301"));

            JsonNode schema = objectMapper.readTree("""
                {
                  "sections": [
                    {
                      "id": "basic",
                      "title": "基本情報",
                      "fields": [
                        { "id": "propertyName", "label": "物件名", "type": "TEXT",
                          "autoFillFrom": "organization.name" },
                        { "id": "unitNumber", "label": "部屋番号", "type": "TEXT",
                          "autoFillFrom": "dwelling_unit.unit_number" },
                        { "id": "memo", "label": "備考", "type": "TEXTAREA" }
                      ]
                    }
                  ]
                }
                """);

            Map<String, Object> filled = service.autoFillAll(schema,
                    AutoFillContext.minimal("ORGANIZATION", 1L));

            assertThat(filled)
                    .containsEntry("propertyName", "Acme")
                    .containsEntry("unitNumber", "301")
                    .doesNotContainKey("memo"); // autoFillFrom 無しは出力されない
        }

        @Test
        @DisplayName("autoFillFilter は当該フィールド固有の filter として Source へ渡る")
        void autoFillFilterApplied() throws Exception {
            StubSource stub = new StubSource("property_history.packages", List.of());
            DisclosureAutoFillService service = newService(stub);

            JsonNode schema = objectMapper.readTree("""
                {
                  "sections": [{
                    "id": "history", "title": "履歴",
                    "fields": [{
                      "id": "renovations", "label": "工事履歴", "type": "AUTO_TABLE",
                      "autoFillFrom": "property_history.packages",
                      "autoFillFilter": { "isDisclosable": true, "status": "COMPLETED" }
                    }]
                  }]
                }
                """);

            service.autoFillAll(schema, AutoFillContext.minimal("ORGANIZATION", 1L));

            assertThat(stub.lastContext).isNotNull();
            assertThat(stub.lastContext.filter())
                    .containsEntry("isDisclosable", Boolean.TRUE)
                    .containsEntry("status", "COMPLETED");
        }

        @Test
        @DisplayName("schema が null/非オブジェクトなら空 Map を返す")
        void nullOrInvalidSchema() throws Exception {
            DisclosureAutoFillService service = newService(
                    new StubSource("organization.name", "X"));
            assertThat(service.autoFillAll(null, AutoFillContext.minimal("ORG", 1L))).isEmpty();
            assertThat(service.autoFillAll(objectMapper.readTree("[]"),
                    AutoFillContext.minimal("ORG", 1L))).isEmpty();
        }

        @Test
        @DisplayName("allowPersonalInfo=false で渡した context は各 Source までそのまま伝播する")
        void personalInfoFlagPropagates() throws Exception {
            StubSource owner = new StubSource("dwelling_unit.owner", "山田太郎");
            DisclosureAutoFillService service = newService(owner);

            JsonNode schema = objectMapper.readTree("""
                {
                  "sections": [{
                    "id": "owner", "title": "所有者",
                    "fields": [{
                      "id": "ownerName", "label": "所有者氏名", "type": "TEXT",
                      "autoFillFrom": "dwelling_unit.owner"
                    }]
                  }]
                }
                """);

            AutoFillContext ctx = new AutoFillContext("ORGANIZATION", 1L, 10L, false, Map.of());
            service.autoFillAll(schema, ctx);

            assertThat(owner.lastContext.allowPersonalInfo()).isFalse();
            assertThat(owner.lastContext.targetDwellingUnitId()).isEqualTo(10L);
        }
    }

    @Nested
    @DisplayName("AutoFillContext")
    class Context {

        @Test
        @DisplayName("filter null は空 Map に正規化される")
        void filterNullNormalized() {
            AutoFillContext ctx = new AutoFillContext("ORG", 1L, null, false, null);
            assertThat(ctx.filter()).isEmpty();
        }

        @Test
        @DisplayName("filter は不変 Map（外部からの変更不可）")
        void filterUnmodifiable() {
            Map<String, Object> input = new HashMap<>();
            input.put("isDisclosable", true);
            AutoFillContext ctx = new AutoFillContext("ORG", 1L, null, false, input);
            assertThatThrownBy(() -> ctx.filter().put("evil", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
