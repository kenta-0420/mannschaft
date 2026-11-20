package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.UpdateEffortTemplateSettingsRequest;
import com.mannschaft.app.effortrecord.entity.EffortDefinitionEntity;
import com.mannschaft.app.effortrecord.entity.EffortDefinitionRevisionEntity;
import com.mannschaft.app.effortrecord.enums.EffortSemantic;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import com.mannschaft.app.effortrecord.repository.EffortDefinitionRepository;
import com.mannschaft.app.effortrecord.repository.EffortDefinitionRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 TEAM 定義の revision 発行・指標設定更新の TX 本体（API 契約 §3・§7、データ設計 §2）。
 *
 * <p>activity テンプレートの現定義は非 TX の Facade が activity Service から取得して渡す（activity の TX を共有しない。
 * 本クラスは他ドメインの Repository / Service に触れない）。定義行は source_key で一意、revision の発行は
 * 定義行の行ロック下で直列化する。canonical 内容（revision 番号を除く）が現行 revision と同じなら revision を増やさない。
 * READ COMMITTED にして、ロック待ちの後で他 TX が確定した revision 行を読めるようにする。</p>
 *
 * <p>チーム側は team_id 基準で、組織 ID を保存も判定もしない（2026-11-18 マスター裁定）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffortTeamDefinitionTxService {

    private final EffortDefinitionRepository definitions;
    private final EffortDefinitionRevisionRepository revisions;
    private final EffortSnapshotCodec codec;
    @Qualifier("utcClock")
    private final Clock clock;

    /** 同期後の現行定義。version は定義行の楽観排他 version（指標設定 API の version）。 */
    public record Current(UUID definitionUuid, long version, int revision, EffortDefinitionSnapshot snapshot,
                          String definitionHash, boolean retired) {
    }

    /**
     * activity の現定義を読む関数（呼び出し側 Facade が実装する）。TX 本体は他ドメインの型・Service に依存しないため、
     * 読み出しを関数として受ける。定義行の行ロックを取った後に呼び、ロック前に取得した古い写しで新しい revision を
     * 巻き戻さないために使う（ロック前の写しは別要求が先に確定した revision より古い可能性がある）。
     */
    @FunctionalInterface
    public interface TemplateLoader {
        Optional<EffortTeamTemplate> load();
    }

    /**
     * 行ロック取得後に activity の現定義を読み直す。ロック保持中に activity から消えていた（廃止された）ときは、
     * 何も保存せず EFFORT_002（409）とする（廃止の記録は次回の呼び出しが markRetired で行う）。
     */
    private static EffortTeamTemplate loadUnderLock(TemplateLoader loader) {
        return loader.load().orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_002));
    }

    /** TEAM 定義の source_key。 */
    public static String sourceKey(Long teamId, Long templateId) {
        return "team:" + teamId + ":template:" + templateId;
    }

    /** 定義行が既にあるか（初回作成の要否判定）。 */
    @Transactional(readOnly = true)
    public boolean exists(Long teamId, Long templateId) {
        return definitions.findBySourceTypeAndSourceKey(EffortSourceType.TEAM, sourceKey(teamId, templateId)).isPresent();
    }

    /**
     * 定義行と revision 1 を作る（初回利用時）。同じ source_key の同時作成は一意制約で片方が
     * DataIntegrityViolationException になるため、呼び出し側（Facade）が再読込で収束させる。
     */
    @Transactional
    public void create(Long teamId, EffortTeamTemplate template) {
        Instant now = now();
        UUID definitionUuid = com.mannschaft.app.common.UuidV7.generate();
        EffortDefinitionSnapshot snapshot = EffortTeamDefinitionBuilder.build(definitionUuid, 1, template,
                EffortTeamDefinitionBuilder.Settings.empty());
        EffortDefinitionEntity definition = EffortDefinitionEntity.builder()
                .sourceType(EffortSourceType.TEAM)
                .sourceKey(sourceKey(teamId, template.id()))
                .teamId(teamId)
                .teamTemplateId(template.id())
                .currentRevision(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        definition.setId(definitionUuid);
        definitions.saveAndFlush(definition);
        insertRevision(definitionUuid, snapshot, now);
        log.info("頑張り記録のチーム定義を作成: teamId={}, templateId={}, definitionUuid={}",
                teamId, template.id(), definitionUuid);
    }

    /**
     * activity の現定義と保存済みの指標設定から canonical 内容を組み直し、現行 revision と異なれば新 revision を発行する。
     * 定義行が無ければ empty（呼び出し側が {@link #create} する）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Current> sync(Long teamId, Long templateId, TemplateLoader loader) {
        return definitions.findBySourceKeyForUpdate(EffortSourceType.TEAM, sourceKey(teamId, templateId))
                .map(definition -> {
                    EffortTeamTemplate template = loadUnderLock(loader);
                    EffortDefinitionSnapshot current = currentSnapshot(definition);
                    EffortDefinitionSnapshot candidate = EffortTeamDefinitionBuilder.build(definition.getId(),
                            definition.getCurrentRevision(), template, EffortTeamDefinitionBuilder.settingsOf(current));
                    return apply(definition, current, candidate);
                });
    }

    /**
     * 指標設定を全置換で更新する（API 契約 §7）。version 不一致は 409、設定の不備は 400 で、いずれも何も保存しない。
     * 要求の fieldKey は現行定義（activity の現定義＋保存済み設定）の key で受け、activity field ID へ読み替えて保存する。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Current updateSettings(Long teamId, Long templateId, TemplateLoader loader,
                                  UpdateEffortTemplateSettingsRequest request) {
        EffortDefinitionEntity definition = definitions
                .findBySourceKeyForUpdate(EffortSourceType.TEAM, sourceKey(teamId, templateId))
                .orElseThrow(() -> new IllegalStateException("チーム定義が未作成のまま指標設定を更新しようとしました"));
        if (definition.getVersion() != request.version().longValue()) {
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }
        EffortTeamTemplate template = loadUnderLock(loader);
        EffortDefinitionSnapshot current = currentSnapshot(definition);
        EffortDefinitionSnapshot live = EffortTeamDefinitionBuilder.build(definition.getId(),
                definition.getCurrentRevision(), template, EffortTeamDefinitionBuilder.settingsOf(current));
        EffortTeamDefinitionBuilder.Settings settings = validatedSettings(live, request);
        EffortDefinitionSnapshot candidate = EffortTeamDefinitionBuilder.build(definition.getId(),
                definition.getCurrentRevision(), template, settings);
        Current result = apply(definition, current, candidate);
        log.info("頑張り記録の指標設定を更新: teamId={}, templateId={}, definitionUuid={}, revision={}",
                teamId, template.id(), result.definitionUuid(), result.revision());
        return result;
    }

    /**
     * activity からテンプレートが消えた（廃止・論理削除）とき、既知の定義なら retired_at を立てて true を返す。
     * 未知（初めから無い・別チーム）なら false。revision は物理削除しない。
     */
    @Transactional
    public boolean markRetired(Long teamId, Long templateId) {
        return definitions.findBySourceKeyForUpdate(EffortSourceType.TEAM, sourceKey(teamId, templateId))
                .map(definition -> {
                    if (definition.getRetiredAt() == null) {
                        definition.setRetiredAt(now());
                        definitions.saveAndFlush(definition);
                        log.info("頑張り記録のチーム定義を廃止扱いにした: teamId={}, templateId={}, definitionUuid={}",
                                teamId, templateId, definition.getId());
                    }
                    return true;
                })
                .orElse(false);
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    private Current apply(EffortDefinitionEntity definition, EffortDefinitionSnapshot current,
                          EffortDefinitionSnapshot candidate) {
        if (sameContent(current, candidate)) {
            return toCurrent(definition, current);
        }
        int next = definition.getCurrentRevision() + 1;
        EffortDefinitionSnapshot issued = EffortTeamDefinitionBuilder.withRevision(candidate, next);
        Instant now = now();
        insertRevision(definition.getId(), issued, now);
        definition.setCurrentRevision(next);
        EffortDefinitionEntity saved = definitions.saveAndFlush(definition);
        return toCurrent(saved, issued);
    }

    private Current toCurrent(EffortDefinitionEntity definition, EffortDefinitionSnapshot snapshot) {
        return new Current(definition.getId(), definition.getVersion(), snapshot.revision(), snapshot,
                codec.hash(snapshot), definition.getRetiredAt() != null);
    }

    private EffortDefinitionSnapshot currentSnapshot(EffortDefinitionEntity definition) {
        EffortDefinitionRevisionEntity revision = revisions
                .findByDefinitionIdAndRevision(definition.getId(), definition.getCurrentRevision())
                .orElseThrow(() -> new IllegalStateException("チーム定義の現行 revision がありません: " + definition.getId()));
        return codec.parseSnapshot(revision.getDefinitionJson());
    }

    private void insertRevision(UUID definitionUuid, EffortDefinitionSnapshot snapshot, Instant now) {
        String json = codec.canonicalJson(snapshot);
        revisions.saveAndFlush(EffortDefinitionRevisionEntity.builder()
                .definitionId(definitionUuid)
                .revision(snapshot.revision())
                .definitionJson(json)
                .definitionHash(codec.hash(json))
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /** revision 番号を除いた canonical 内容が同じか。 */
    private boolean sameContent(EffortDefinitionSnapshot a, EffortDefinitionSnapshot b) {
        return codec.canonicalJson(EffortTeamDefinitionBuilder.withRevision(a, 0))
                .equals(codec.canonicalJson(EffortTeamDefinitionBuilder.withRevision(b, 0)));
    }

    /** 要求を検証し、activity field ID 単位の設定へ読み替える。不備は fieldErrors（fields.*）付きの 400。 */
    private static EffortTeamDefinitionBuilder.Settings validatedSettings(EffortDefinitionSnapshot live,
                                                                        UpdateEffortTemplateSettingsRequest request) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        Map<String, com.mannschaft.app.effortrecord.dto.EffortFieldDefinition> byKey = new LinkedHashMap<>();
        live.fields().forEach(f -> byKey.put(f.fieldKey(), f));

        Map<Long, EffortTeamDefinitionBuilder.FieldSetting> byId = new HashMap<>();
        Map<String, EffortTeamDefinitionBuilder.FieldSetting> byRequestKey = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int durations = 0;
        int subjects = 0;
        if (request.fields().isEmpty()) {
            errors.add(error("fields", "全項目の設定を指定してください"));
        }
        for (UpdateEffortTemplateSettingsRequest.FieldSetting input : request.fields()) {
            String path = "fields." + input.fieldKey();
            var field = byKey.get(input.fieldKey());
            if (field == null) {
                errors.add(error(path, "テンプレートにない項目です"));
                continue;
            }
            if (!seen.add(input.fieldKey())) {
                errors.add(error(path, "項目が重複しています"));
                continue;
            }
            EffortTeamDefinitionBuilder.FieldSetting setting =
                    new EffortTeamDefinitionBuilder.FieldSetting(input.semantic(), input.unit(), input.aggregation());
            Optional<String> incompatible = EffortTeamDefinitionBuilder.incompatibility(field.type(), setting);
            if (incompatible.isPresent()) {
                errors.add(error(path, incompatible.get()));
                continue;
            }
            if (setting.semantic() == EffortSemantic.DURATION) {
                durations++;
            }
            if (setting.semantic() == EffortSemantic.SUBJECT) {
                subjects++;
            }
            byRequestKey.put(input.fieldKey(), setting);
            EffortTeamDefinitionBuilder.fieldIdOf(input.fieldKey()).ifPresent(id -> byId.put(id, setting));
        }
        for (String key : byKey.keySet()) {
            if (!seen.contains(key)) {
                errors.add(error("fields." + key, "項目の設定がありません"));
            }
        }
        if (durations > 1) {
            errors.add(error("fields", "時間（DURATION）は1項目だけ設定できます"));
        }
        if (subjects > 1) {
            errors.add(error("fields", "科目（SUBJECT）は1項目だけ設定できます"));
        }
        Long durationId = mappedField(request.durationFieldKey(), EffortSemantic.DURATION, byRequestKey,
                "durationFieldKey", errors);
        Long subjectId = mappedField(request.subjectFieldKey(), EffortSemantic.SUBJECT, byRequestKey,
                "subjectFieldKey", errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_001, errors);
        }
        return new EffortTeamDefinitionBuilder.Settings(byId, durationId, subjectId);
    }

    /** duration/subject の対応先。null は対応なし、対応先は同じ要求で該当 semantic を設定した field に限る。 */
    private static Long mappedField(String key, EffortSemantic expected,
                                    Map<String, EffortTeamDefinitionBuilder.FieldSetting> byRequestKey,
                                    String path, List<ErrorResponse.FieldError> errors) {
        if (key == null) {
            return null;
        }
        EffortTeamDefinitionBuilder.FieldSetting setting = byRequestKey.get(key);
        if (setting == null || setting.semantic() != expected) {
            errors.add(error("fields." + path, "対応先には" + expected.name() + "を設定した項目を指定してください"));
            return null;
        }
        return EffortTeamDefinitionBuilder.fieldIdOf(key).orElse(null);
    }

    private static ErrorResponse.FieldError error(String field, String message) {
        return new ErrorResponse.FieldError(field, message);
    }

    /** DATETIME(6) と同じ精度へ丸める。 */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
