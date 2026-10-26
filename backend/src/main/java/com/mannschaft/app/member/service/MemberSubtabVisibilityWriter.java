package com.mannschaft.app.member.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.MemberErrorCode;
import com.mannschaft.app.member.MemberSubtabDefaultMinRoleMap;
import com.mannschaft.app.member.MemberSubtabKey;
import com.mannschaft.app.member.dto.UpdateMemberSubtabVisibilityRequest;
import com.mannschaft.app.member.entity.MemberSubtabRoleVisibilityEntity;
import com.mannschaft.app.member.event.MemberSubtabVisibilityUpdatedEvent;
import com.mannschaft.app.member.repository.MemberSubtabRoleVisibilityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * サブタブ可視性設定の書き込み専用部品（F06.6・PR #3387 D-3T 根治）。
 *
 * <p>member ドメインの書き込み TX はここ（{@link #applyUpdates}）だけに閉じる。権限確認・表示名の解決
 * （他ドメインの読み取り）は、TX を持たない段取り役 {@link MemberSubtabVisibilityService} が
 * この TX の<b>外</b>で済ませる。同じクラス内から呼ぶとプロキシを通らず TX が掛からないため、別 Bean にしている。</p>
 *
 * <p>監査ログは auth ドメインを直接呼ばず、{@link MemberSubtabVisibilityUpdatedEvent} を発行して
 * {@code AuditLogEventListener} に AFTER_COMMIT で記録させる。ロールバックすれば監査は出ない。</p>
 *
 * <p>設計: {@code .claude/campaigns/gungi-3387-d3t.md} §2.1(c)(d)、docs/features/F06.6_member_subtab_visibility.md</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberSubtabVisibilityWriter {

    private final MemberSubtabRoleVisibilityRepository repository;
    private final DomainEventPublisher domainEventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 更新項目を1つの TX で適用する。途中で例外（422 等）が出れば全件が取り消される。
     * 差分があれば同じ TX の中で監査イベントを発行し、最後にコミット前の同じ TX で設定を再読込して返す。
     *
     * @return 応答組み立て用のスナップショット（スカラーのみ）
     */
    @Transactional
    public MemberSubtabVisibilitySnapshot applyUpdates(ScopeType scopeType, Long scopeId, Long actorUserId,
                                                       List<UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem> items) {
        List<Map<String, Object>> changes = new ArrayList<>();
        for (UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem update : items) {
            applyOneUpdate(scopeType, scopeId, actorUserId, update, changes);
        }

        if (!changes.isEmpty()) {
            publishAuditEvent(scopeType, scopeId, actorUserId, changes);
        }

        return MemberSubtabVisibilitySnapshot.of(repository.findByScopeTypeAndScopeId(scopeType, scopeId));
    }

    // ─────────────────────────────────────────────
    // 個別更新ロジック
    // ─────────────────────────────────────────────

    private void applyOneUpdate(ScopeType scope, Long scopeId, Long currentUserId,
                                 UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem update,
                                 List<Map<String, Object>> changes) {
        if (update == null || update.getMinRole() == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }

        MemberSubtabKey key = parseSubtabKey(update.getSubtabKey());
        MinRole newMinRole = update.getMinRole();

        // 一覧タブに PUBLIC を設定しようとしたら 422 で拒否（氏名・役割等を含むため）
        if (newMinRole == MinRole.PUBLIC && !MemberSubtabDefaultMinRoleMap.isPublicAllowed(key)) {
            log.warn("MemberSubtabVisibilityWriter: 一覧タブへの PUBLIC 設定は拒否 "
                    + "(scopeType={}, scopeId={}, userId={})", scope, scopeId, currentUserId);
            throw new BusinessException(MemberErrorCode.MEMBER_LIST_PUBLIC_NOT_ALLOWED);
        }

        MinRole defaultMinRole = MemberSubtabDefaultMinRoleMap.getDefault(key);

        Optional<MemberSubtabRoleVisibilityEntity> existing =
                repository.findByScopeTypeAndScopeIdAndSubtabKey(scope, scopeId, key.getDbValue());

        MinRole beforeMinRole = existing.map(MemberSubtabRoleVisibilityEntity::getMinRole).orElse(defaultMinRole);

        if (newMinRole == defaultMinRole) {
            if (existing.isPresent()) {
                repository.deleteByScopeTypeAndScopeIdAndSubtabKey(scope, scopeId, key.getDbValue());
                addChangeIfDifferent(changes, key, beforeMinRole, newMinRole);
            }
            return;
        }

        if (existing.isPresent()) {
            MemberSubtabRoleVisibilityEntity entity = existing.get();
            if (entity.getMinRole() != newMinRole) {
                entity.changeMinRole(newMinRole, currentUserId);
                repository.save(entity);
                addChangeIfDifferent(changes, key, beforeMinRole, newMinRole);
            }
        } else {
            MemberSubtabRoleVisibilityEntity entity = MemberSubtabRoleVisibilityEntity.builder()
                    .scopeType(scope)
                    .scopeId(scopeId)
                    .subtabKey(key.getDbValue())
                    .minRole(newMinRole)
                    .updatedBy(currentUserId)
                    .build();
            repository.save(entity);
            addChangeIfDifferent(changes, key, beforeMinRole, newMinRole);
        }
    }

    private static void addChangeIfDifferent(List<Map<String, Object>> changes, MemberSubtabKey key,
                                               MinRole before, MinRole after) {
        if (before == after) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("subtab_key", key.getDbValue());
        entry.put("before", before.name());
        entry.put("after", after.name());
        changes.add(entry);
    }

    private static MemberSubtabKey parseSubtabKey(String subtabKey) {
        if (subtabKey == null || subtabKey.isBlank()) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        try {
            return MemberSubtabKey.fromDbValue(subtabKey);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    // ─────────────────────────────────────────────
    // 監査イベント
    // ─────────────────────────────────────────────

    /**
     * 監査イベントを発行する。metadata の組み立ては修正前の {@code recordAuditLog} と一字一句同じ
     * （キー順 scope_type→scope_id→changes、直列化失敗時は {@code "{}"}）。
     */
    private void publishAuditEvent(ScopeType scope, Long scopeId, Long currentUserId,
                                   List<Map<String, Object>> changes) {
        Long teamId = scope == ScopeType.TEAM ? scopeId : null;
        Long organizationId = scope == ScopeType.ORGANIZATION ? scopeId : null;

        Map<String, Object> metadataMap = new LinkedHashMap<>();
        metadataMap.put("scope_type", scope.name());
        metadataMap.put("scope_id", scopeId);
        metadataMap.put("changes", changes);

        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metadataMap);
        } catch (JsonProcessingException ex) {
            log.warn("MemberSubtabVisibilityWriter: 監査ログ metadata の JSON 直列化失敗 "
                    + "(userId={})", currentUserId, ex);
            metadataJson = "{}";
        }

        domainEventPublisher.publish(
                new MemberSubtabVisibilityUpdatedEvent(currentUserId, teamId, organizationId, metadataJson));
    }
}
