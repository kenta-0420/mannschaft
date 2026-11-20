package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.dto.CreateDefinitionSnapshotRequest;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionTokenResponse;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.entity.EffortDefinitionEntity;
import com.mannschaft.app.effortrecord.entity.EffortDefinitionRevisionEntity;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import com.mannschaft.app.effortrecord.repository.EffortDefinitionRepository;
import com.mannschaft.app.effortrecord.repository.EffortDefinitionRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F06.4.1 定義 Service。標準プリセットの取得と、原本作成時の定義 snapshot 解決を担う。
 *
 * <p>チーム定義の revision 登録・署名 token 発行（registerTeamSnapshot、PR-2）も担う。チーム側は team_id 基準で、
 * 組織 ID を判定・保存・token のいずれにも使わない（2026-11-18 マスター裁定）。</p>
 */
@Service
@RequiredArgsConstructor
public class EffortDefinitionService {

    private final EffortDefinitionRepository definitions;
    private final EffortDefinitionRevisionRepository revisions;
    private final EffortSnapshotCodec codec;
    private final EffortTeamAccessGuard teamAccess;
    private final EffortTeamDefinitionResolver teamDefinitions;
    private final EffortDefinitionTokenCodec tokens;

    /** 原本作成時に解決した定義。snapshotJson は revision の保存 JSON（原本へ複写する正本）。 */
    public record ResolvedDefinition(EffortDefinitionEntity definition, int revision,
                                     EffortDefinitionSnapshot snapshot, String snapshotJson) {
    }

    /** 標準プリセット 3 種（STUDY_V1 / EXERCISE_V1 / WEIGHT_V1）を返す。所属不要。固定 UUID 昇順。 */
    @Transactional(readOnly = true)
    public List<EffortDefinitionSnapshot> listPresets() {
        List<EffortDefinitionEntity> presets = definitions.findBySourceTypeOrderBySourceKeyAsc(EffortSourceType.PRESET);
        Map<String, EffortDefinitionRevisionEntity> byKey = revisions
                .findByDefinitionIdIn(presets.stream().map(EffortDefinitionEntity::getId).toList()).stream()
                .collect(Collectors.toMap(r -> r.getDefinitionId() + "#" + r.getRevision(), Function.identity()));
        return presets.stream()
                .sorted(Comparator.comparing(EffortDefinitionEntity::getId))
                .map(def -> {
                    EffortDefinitionRevisionEntity revision = byKey.get(def.getId() + "#" + def.getCurrentRevision());
                    if (revision == null) {
                        throw new IllegalStateException("標準プリセットの revision がありません: " + def.getSourceKey());
                    }
                    return codec.parseSnapshot(revision.getDefinitionJson());
                })
                .toList();
    }

    /**
     * チーム定義の不変 revision を登録し、10 分有効の definitionToken を返す（API 契約 §3 / §7、AC-19）。
     *
     * <p>現在所属（非所属・脱退済み・SYSTEM_ADMIN の非所属は 403 COMMON_002。template の存否で応答を変えない）を
     * 先に確認し、次に path の team に属する有効な TEAM template か（別チーム・組織スコープ・不在は 404 EFFORT_001、
     * 廃止は 409 EFFORT_002）を確かめる。activity の現定義と指標設定の canonical 内容が現行 revision と同じなら
     * revision を増やさず、異なれば同一 effort TX で新 revision を発行する。原本は作らない。
     * 親組織の有無・一致は判定にも token にも使わない（2026-11-18 マスター裁定）。</p>
     */
    public EffortDefinitionTokenResponse registerTeamSnapshot(Long userId, CreateDefinitionSnapshotRequest request) {
        teamAccess.requireMember(userId, request.teamId());
        EffortTeamDefinitionTxService.Current current = teamDefinitions.sync(request.teamId(), request.teamTemplateId());
        EffortDefinitionTokenCodec.Issued issued = tokens.issue(userId, request.teamId(), current.definitionUuid(),
                current.revision(), current.definitionHash());
        return new EffortDefinitionTokenResponse(current.snapshot(), issued.token(), issued.expiresAt());
    }

    /**
     * 原本作成用に定義 revision を解決する。
     *
     * <p>標準プリセットは token 不要（指定されたら拒否）。TEAM 定義は {@code teamToken}（Facade で署名・期限・本人・
     * 定義 UUID・revision の束縛を検証済み）が必須で、token の teamId と定義の team、token の hash と保存済み revision の
     * hash が一致しなければ 400（fieldErrors=definitionToken）。snapshot 本体はクライアントから受け取らず、
     * サーバ保存の revision を複写する。不明な定義・revision は 400。</p>
     */
    @Transactional(readOnly = true)
    public ResolvedDefinition resolveForCreate(UUID definitionUuid, int revision,
                                               EffortDefinitionTokenCodec.Claims teamToken) {
        EffortDefinitionEntity definition = definitions.findById(definitionUuid)
                .orElseThrow(() -> invalid("definitionUuid", "定義が見つかりません"));
        EffortDefinitionRevisionEntity found;
        if (definition.getSourceType() == EffortSourceType.PRESET) {
            if (teamToken != null) {
                throw EffortDefinitionTokenCodec.invalid();
            }
            found = revisions.findByDefinitionIdAndRevision(definitionUuid, revision)
                    .orElseThrow(() -> invalid("definitionRevision", "定義の版が見つかりません"));
        } else {
            if (teamToken == null || !teamToken.teamId().equals(definition.getTeamId())) {
                throw EffortDefinitionTokenCodec.invalid();
            }
            found = revisions.findByDefinitionIdAndRevision(definitionUuid, revision)
                    .filter(r -> r.getDefinitionHash().equals(teamToken.definitionHash()))
                    .orElseThrow(EffortDefinitionTokenCodec::invalid);
        }
        return new ResolvedDefinition(definition, revision, codec.parseSnapshot(found.getDefinitionJson()),
                found.getDefinitionJson());
    }

    /** 集計用: 定義と現行 revision の snapshot。不在は empty。 */
    @Transactional(readOnly = true)
    public java.util.Optional<ResolvedDefinition> findCurrent(UUID definitionUuid) {
        return definitions.findById(definitionUuid).flatMap(definition ->
                revisions.findByDefinitionIdAndRevision(definitionUuid, definition.getCurrentRevision())
                        .map(r -> new ResolvedDefinition(definition, r.getRevision(),
                                codec.parseSnapshot(r.getDefinitionJson()), r.getDefinitionJson())));
    }

    /** 集計用: 指定 revision 群の snapshot を一括で引く（原本が凍結した版。行ごとの照会をしない）。 */
    @Transactional(readOnly = true)
    public Map<Integer, EffortDefinitionSnapshot> findRevisions(UUID definitionUuid, java.util.Collection<Integer> revisionNumbers) {
        if (revisionNumbers.isEmpty()) {
            return Map.of();
        }
        Map<Integer, EffortDefinitionSnapshot> result = new java.util.HashMap<>();
        revisions.findByDefinitionIdAndRevisionIn(definitionUuid, revisionNumbers)
                .forEach(r -> result.put(r.getRevision(), codec.parseSnapshot(r.getDefinitionJson())));
        return result;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(field, message)));
    }
}
