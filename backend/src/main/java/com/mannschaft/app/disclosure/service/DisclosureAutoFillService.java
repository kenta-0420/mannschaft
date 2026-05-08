package com.mannschaft.app.disclosure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 重要事項説明書（F09.14）の自動引用エンジン。
 *
 * <p>設計書 F09.14 §5.2「自動引用元（autoFillFrom）」および
 * §6.4「Auto-fill のクロスサイト対策」に対応。
 * {@link AutoFillSource} 実装を Spring から自動収集してホワイトリスト
 * （{@code SOURCE_REGISTRY}）を構築し、{@code form_schema} の
 * {@code autoFillFrom} キーを介した安全な参照のみを許容する。</p>
 *
 * <p>新たな引用元を追加する場合、{@link AutoFillSource} を実装した {@code @Component} を
 * 1 つ追加するだけで本サービスとフォームバリデータの双方が自動的に追従する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DisclosureAutoFillService {

    private final List<AutoFillSource> sources;

    /** 起動時に構築する不変ホワイトリスト（key -> Source）。 */
    private Map<String, AutoFillSource> registry;

    @PostConstruct
    void init() {
        Map<String, AutoFillSource> map = sources.stream()
                .collect(Collectors.toUnmodifiableMap(
                        AutoFillSource::key,
                        Function.identity(),
                        (a, b) -> {
                            // 同じ key を返す Source が 2 つ登録されていれば設計バグ。即座に検知。
                            throw new IllegalStateException(
                                    "Duplicate AutoFillSource key: " + a.key()
                                            + " (" + a.getClass().getName()
                                            + " vs " + b.getClass().getName() + ")");
                        }));
        this.registry = map;
        log.info("DisclosureAutoFillService initialized with {} source(s): {}",
                map.size(), map.keySet());
    }

    /**
     * ホワイトリストに登録された全引用元キーを返す（バリデータが利用）。
     */
    public Set<String> registeredKeys() {
        return registry.keySet();
    }

    /**
     * 引用元キーから値を取得する。
     *
     * <p>未登録キーは {@link IllegalArgumentException} を投げる。設計書 §6.4 の
     * 「ホワイトリスト方式」に従い、サイレントに無視せず明示的に拒否する。</p>
     *
     * @param sourceKey 引用元キー（例: {@code "organization.name"}）
     * @param context   解決コンテキスト
     * @return 解決値。該当データなしの場合は {@code null}
     */
    public Object resolve(String sourceKey, AutoFillContext context) {
        AutoFillSource source = registry.get(sourceKey);
        if (source == null) {
            throw new IllegalArgumentException(
                    "Unknown autoFillFrom key: " + sourceKey
                            + " (registered: " + registry.keySet() + ")");
        }
        try {
            return source.resolve(context);
        } catch (RuntimeException e) {
            // 設計書 §4 DISCLOSURE_008「自動引用エラー」相当だが、本サービスは値を返すだけで
            // 例外変換は呼出元（API 層）に任せる。ここではログだけ残して null を返す。
            log.warn("AutoFillSource[{}] failed to resolve: {}", sourceKey, e.toString());
            return null;
        }
    }

    /**
     * {@code form_schema} を走査し、全 {@code autoFillFrom} フィールドの値を引いて
     * {@code field.id -> 値} の Map を返す。
     *
     * <p>{@code autoFillFilter} は当該フィールド固有の filter として AutoFillContext.filter を
     * 上書きしたコンテキストで解決する。</p>
     *
     * @param formSchema {@code form_schema} JSON ルート
     * @param context    全体に適用する基本コンテキスト
     * @return field.id をキーとする値マップ。schema が不正な場合は空 Map
     */
    public Map<String, Object> autoFillAll(JsonNode formSchema, AutoFillContext context) {
        if (formSchema == null || !formSchema.isObject()) {
            return Map.of();
        }
        JsonNode sections = formSchema.get("sections");
        if (sections == null || !sections.isArray()) {
            return Map.of();
        }

        // 出力順を schema 走査順に揃えるため LinkedHashMap
        Map<String, Object> result = new LinkedHashMap<>();
        for (JsonNode section : sections) {
            JsonNode fields = section.get("fields");
            if (fields == null || !fields.isArray()) {
                continue;
            }
            for (JsonNode field : fields) {
                JsonNode autoFillFromNode = field.get("autoFillFrom");
                if (autoFillFromNode == null || !autoFillFromNode.isTextual()) {
                    continue;
                }
                JsonNode fieldIdNode = field.get("id");
                if (fieldIdNode == null || !fieldIdNode.isTextual()) {
                    continue;
                }
                String fieldId = fieldIdNode.asText();
                String sourceKey = autoFillFromNode.asText();

                AutoFillContext effective = context;
                JsonNode filterNode = field.get("autoFillFilter");
                if (filterNode != null && filterNode.isObject()) {
                    effective = withFilter(context, filterNode);
                }

                try {
                    Object value = resolve(sourceKey, effective);
                    result.put(fieldId, value);
                } catch (IllegalArgumentException e) {
                    // 未登録キー: バリデータでブロックすべきだが、防御的にスキップ
                    log.warn("Skipping unknown autoFillFrom key '{}' for field '{}'",
                            sourceKey, fieldId);
                }
            }
        }
        return result;
    }

    /**
     * filter ノードを Java Map に正規化して context を更新する。
     */
    private AutoFillContext withFilter(AutoFillContext base, JsonNode filterNode) {
        Map<String, Object> filter = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = filterNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            filter.put(e.getKey(), normalize(e.getValue()));
        }
        return new AutoFillContext(
                base.scopeType(),
                base.scopeId(),
                base.targetDwellingUnitId(),
                base.allowPersonalInfo(),
                filter);
    }

    /**
     * Jackson JsonNode → Java スカラ / List への変換（filter 値用、深い変換は不要）。
     */
    private Object normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<Object> list = new java.util.ArrayList<>(node.size());
            for (JsonNode child : node) {
                list.add(normalize(child));
            }
            return list;
        }
        // Object はそのまま JsonNode で返す（現状の Source は使わない）
        return node;
    }
}
