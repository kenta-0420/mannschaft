package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortFieldDefinition;
import com.mannschaft.app.effortrecord.enums.EffortAggregation;
import com.mannschaft.app.effortrecord.enums.EffortFieldType;
import com.mannschaft.app.effortrecord.enums.EffortSemantic;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F06.4.1 チームテンプレート（activity）＋effort 指標設定から TEAM 定義 snapshot を組み立てる純粋関数群
 * （API 契約 §7、親設計 §3）。DB には触れない。
 *
 * <h2>stable field key（series identity）</h2>
 * <p>{@code f{activity field ID}_{hash10}}。hash10 は「型・semantic・canonical unit」の SHA-256 先頭 10 桁。
 * したがって label（表示名）の変更は同じ key、型・semantic・unit のいずれかの変更や、activity での
 * 削除→再追加（field ID が変わる）は新しい key になる。key は内容から決定的に導くため、同じ key が
 * 別の型・単位・semantic を指すことはない（key の使い回し禁止。殿裁定 2026-11-18・台帳裁定④）。</p>
 *
 * <h2>指標設定の保存先</h2>
 * <p>設定は {@code effort_definition_revisions.definition_json}（＝この snapshot そのもの）に保存する。
 * activity テンプレートの既存列へ属性を足さない。次の revision を組み立てるときは、現行 revision の各 field の
 * key から activity field ID を読み戻し（{@link #settingsOf}）、その ID の設定を引き継ぐ。</p>
 *
 * <h2>min / max / scale</h2>
 * <p>管理者の任意設定にせず semantic の固定規則でサーバが決める（標準プリセットと同じ表記）。</p>
 */
public final class EffortTeamDefinitionBuilder {

    /** NUMBER 等の無単位の canonical 表記。 */
    public static final String NO_UNIT = "NONE";
    /** unit の最大長（API 契約 §2）。 */
    public static final int MAX_UNIT_LENGTH = 32;

    private static final Pattern KEY = Pattern.compile("f([0-9]+)_[0-9a-f]{10}");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private EffortTeamDefinitionBuilder() {
    }

    /** 1 field の指標設定。 */
    public record FieldSetting(EffortSemantic semantic, String unit, EffortAggregation aggregation) {
    }

    /** activity field ID 単位の指標設定一式（duration/subject の対応先も field ID で持つ）。 */
    public record Settings(Map<Long, FieldSetting> byFieldId, Long durationFieldId, Long subjectFieldId) {

        public static Settings empty() {
            return new Settings(Map.of(), null, null);
        }
    }

    /** activity の型を effort の型へ写す（TEXTAREA は TEXT）。未知型は empty。 */
    public static Optional<EffortFieldType> typeOf(String activityType) {
        if (activityType == null) {
            return Optional.empty();
        }
        return switch (activityType) {
            case "NUMBER" -> Optional.of(EffortFieldType.NUMBER);
            case "TEXT", "TEXTAREA" -> Optional.of(EffortFieldType.TEXT);
            case "SELECT" -> Optional.of(EffortFieldType.SELECT);
            case "DATE" -> Optional.of(EffortFieldType.DATE);
            case "DATETIME" -> Optional.of(EffortFieldType.DATETIME);
            case "CHECKBOX" -> Optional.of(EffortFieldType.CHECKBOX);
            default -> Optional.empty();
        };
    }

    /** 指標未設定の既定（数値は NUMBER/NONE で集計対象外、選択肢・自由文は TEXT/NONE）。 */
    public static FieldSetting defaultSetting(EffortFieldType type) {
        return switch (type) {
            case NUMBER -> new FieldSetting(EffortSemantic.NUMBER, NO_UNIT, EffortAggregation.NONE);
            case TEXT, SELECT -> new FieldSetting(EffortSemantic.TEXT, NO_UNIT, EffortAggregation.NONE);
            case DATE -> new FieldSetting(EffortSemantic.DATE, NO_UNIT, EffortAggregation.NONE);
            case DATETIME -> new FieldSetting(EffortSemantic.DATETIME, NO_UNIT, EffortAggregation.NONE);
            case CHECKBOX -> new FieldSetting(EffortSemantic.CHECKBOX, NO_UNIT, EffortAggregation.NONE);
        };
    }

    /**
     * 型×semantic×unit×aggregation の許容組合せか（API 契約 §7・親設計 §3 の表）。
     *
     * @return 不適合の理由。適合なら empty
     */
    public static Optional<String> incompatibility(EffortFieldType type, FieldSetting setting) {
        EffortSemantic semantic = setting.semantic();
        String unit = setting.unit();
        EffortAggregation aggregation = setting.aggregation();
        boolean noUnitNoAgg = NO_UNIT.equals(unit) && aggregation == EffortAggregation.NONE;
        boolean ok = switch (type) {
            case NUMBER -> switch (semantic) {
                case DURATION -> "MINUTE".equals(unit) && aggregation == EffortAggregation.SUM;
                case WEIGHT -> "KILOGRAM".equals(unit) && aggregation == EffortAggregation.LATEST;
                case INTENSITY -> "RPE".equals(unit) && aggregation == EffortAggregation.NONE;
                case NUMBER -> validUnit(unit)
                        && (aggregation == EffortAggregation.SUM || aggregation == EffortAggregation.NONE);
                default -> false;
            };
            case TEXT -> (semantic == EffortSemantic.SUBJECT || semantic == EffortSemantic.MENU
                    || semantic == EffortSemantic.TEXT) && noUnitNoAgg;
            case SELECT -> (semantic == EffortSemantic.SUBJECT || semantic == EffortSemantic.TEXT) && noUnitNoAgg;
            case DATE -> semantic == EffortSemantic.DATE && noUnitNoAgg;
            case DATETIME -> semantic == EffortSemantic.DATETIME && noUnitNoAgg;
            case CHECKBOX -> semantic == EffortSemantic.CHECKBOX && noUnitNoAgg;
        };
        return ok ? Optional.empty() : Optional.of("この項目の型には指定の意味・単位・集計方法を設定できません");
    }

    private static boolean validUnit(String unit) {
        return unit != null && !unit.isBlank() && unit.strip().equals(unit)
                && unit.codePointCount(0, unit.length()) <= MAX_UNIT_LENGTH
                && unit.codePoints().noneMatch(Character::isISOControl);
    }

    /** stable field key（型・semantic・unit と activity field ID から決定的に導く）。 */
    public static String fieldKey(long activityFieldId, EffortFieldType type, FieldSetting setting) {
        String identity = type.name() + "|" + setting.semantic().name() + "|" + setting.unit();
        return "f" + activityFieldId + "_" + sha256(identity).substring(0, 10);
    }

    /** key から activity field ID を読み戻す（TEAM 定義の key 以外は empty）。 */
    public static Optional<Long> fieldIdOf(String fieldKey) {
        if (fieldKey == null) {
            return Optional.empty();
        }
        Matcher matcher = KEY.matcher(fieldKey);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 保存済み TEAM 定義 snapshot から、activity field ID 単位の指標設定を読み戻す。 */
    public static Settings settingsOf(EffortDefinitionSnapshot snapshot) {
        Map<Long, FieldSetting> byId = new HashMap<>();
        for (EffortFieldDefinition field : snapshot.fields()) {
            fieldIdOf(field.fieldKey()).ifPresent(id ->
                    byId.put(id, new FieldSetting(field.semantic(), field.unit(), field.aggregation())));
        }
        return new Settings(byId, fieldIdOf(snapshot.durationFieldKey()).orElse(null),
                fieldIdOf(snapshot.subjectFieldKey()).orElse(null));
    }

    /**
     * activity テンプレートの現定義と指標設定から snapshot を組み立てる。field は activity の並び順（sortOrder, id）。
     * 型に適合しない設定（activity 側で型が変わった等）はその型の既定へ戻す。
     *
     * @throws BusinessException activity の型・選択肢が effort で扱えない場合（COMMON_001 / 400）
     */
    public static EffortDefinitionSnapshot build(UUID definitionUuid, int revision, EffortTeamTemplate template,
                                                 Settings settings) {
        List<EffortTeamTemplate.Field> ordered = new ArrayList<>(template.fields());
        ordered.sort(Comparator.comparing((EffortTeamTemplate.Field f) ->
                        f.sortOrder() == null ? Integer.MAX_VALUE : f.sortOrder())
                .thenComparing(EffortTeamTemplate.Field::id));
        List<EffortFieldDefinition> fields = new ArrayList<>(ordered.size());
        String durationKey = null;
        String subjectKey = null;
        for (EffortTeamTemplate.Field source : ordered) {
            EffortFieldType type = typeOf(source.type())
                    .orElseThrow(() -> unsupportedTemplate("effort で扱えない項目の型です"));
            FieldSetting setting = settings.byFieldId().get(source.id());
            if (setting == null || incompatibility(type, setting).isPresent()) {
                setting = defaultSetting(type);
            }
            String key = fieldKey(source.id(), type, setting);
            if (source.id().equals(settings.durationFieldId()) && setting.semantic() == EffortSemantic.DURATION) {
                durationKey = key;
            }
            if (source.id().equals(settings.subjectFieldId()) && setting.semantic() == EffortSemantic.SUBJECT) {
                subjectKey = key;
            }
            Rule rule = rule(setting.semantic());
            fields.add(new EffortFieldDefinition(key, source.label(), null, type,
                    source.required(), setting.semantic(), setting.unit(), setting.aggregation(),
                    rule.scale(), rule.min(), rule.max(),
                    type == EffortFieldType.SELECT ? options(source.optionsJson()) : List.of()));
        }
        return new EffortDefinitionSnapshot(definitionUuid, revision, EffortSourceType.TEAM, null, template.id(),
                template.name(), durationKey, subjectKey, List.copyOf(fields));
    }

    /** snapshot の revision だけを差し替える（内容比較用）。 */
    public static EffortDefinitionSnapshot withRevision(EffortDefinitionSnapshot s, int revision) {
        return new EffortDefinitionSnapshot(s.definitionUuid(), revision, s.source(), s.presetCode(), s.teamTemplateId(),
                s.templateName(), s.durationFieldKey(), s.subjectFieldKey(), s.fields());
    }

    // ───────────────────────────── 固定規則 ─────────────────────────────

    private record Rule(int scale, String min, String max) {
    }

    /** semantic ごとの入力範囲（親設計 §3 の表。標準プリセットと同じ表記）。 */
    private static Rule rule(EffortSemantic semantic) {
        return switch (semantic) {
            case DURATION -> new Rule(0, "0", "1440");
            case WEIGHT -> new Rule(2, "0.01", "1000.00");
            case INTENSITY -> new Rule(0, "0", "10");
            case NUMBER -> new Rule(2, "0", "999999999.99");
            default -> new Rule(0, null, null);
        };
    }

    /**
     * activity の options_json（文字列配列、または label/value を持つオブジェクト配列）を選択肢へ写す。
     * option key は label の SHA-256 から決定的に導く（同じ label は revision をまたいで同じ key）。
     */
    private static List<EffortFieldDefinition.Option> options(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = JSON.readTree(optionsJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw unsupportedTemplate("選択肢の定義を読み取れません");
        }
        if (root == null || !root.isArray()) {
            throw unsupportedTemplate("選択肢の定義を読み取れません");
        }
        List<EffortFieldDefinition.Option> options = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (JsonNode node : root) {
            String label;
            if (node.isTextual()) {
                label = node.asText();
            } else if (node.isObject() && (node.hasNonNull("label") || node.hasNonNull("value"))) {
                label = node.hasNonNull("label") ? node.get("label").asText() : node.get("value").asText();
            } else {
                throw unsupportedTemplate("選択肢の定義を読み取れません");
            }
            String base = "o_" + sha256(label).substring(0, 12);
            String key = base;
            for (int i = 2; !used.add(key); i++) {
                key = base + "_" + i;
            }
            options.add(new EffortFieldDefinition.Option(key, label, null));
        }
        return List.copyOf(options);
    }

    /** テンプレートの型・選択肢を effort で扱えない（API 契約 §7: 型がない/未知型/複合値は 400）。 */
    private static BusinessException unsupportedTemplate(String message) {
        return new BusinessException(CommonErrorCode.COMMON_001,
                List.of(new ErrorResponse.FieldError("teamTemplateId", message)));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
