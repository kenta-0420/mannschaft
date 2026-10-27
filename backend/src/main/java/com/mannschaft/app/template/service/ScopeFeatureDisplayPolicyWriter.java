package com.mannschaft.app.template.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.template.FeatureDisplayScope;
import com.mannschaft.app.template.dto.FeatureDisplayPoliciesResponse;
import com.mannschaft.app.template.dto.FeatureDisplayPolicyItem;
import com.mannschaft.app.template.event.ScopeFeatureDisplayPolicyUpdatedEvent;
import com.mannschaft.app.template.repository.ScopeFeatureDisplayPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** templateドメインだけの一括保存TX。途中例外は全件rollbackし、監査はcommit後に記録する。 */
@Service
@RequiredArgsConstructor
public class ScopeFeatureDisplayPolicyWriter {
    private final ScopeFeatureDisplayPolicyRepository repository;
    private final DomainEventPublisher events;
    private final ObjectMapper json;

    @Transactional
    public FeatureDisplayPoliciesResponse applyUpdates(Long actor, FeatureDisplayScope scope, Long scopeId,
                                                       List<FeatureDisplayPolicyItem> items) {
        var rows = ScopeFeatureDisplayPolicyService.snapshot(repository.findByScopeTypeAndScopeId(scope, scopeId));
        // 入力順の異なる並行保存でも行ロックを同じ順に取る。初回保存はDBのUPSERTで直列化する。
        for (var item : items.stream().sorted(Comparator.comparing(FeatureDisplayPolicyItem::featureKey)).toList()) {
            repository.upsert(UuidV7.generate(), scope.name(), scopeId, item.featureKey(), item.memberVisible(), item.supporterVisible(), actor);
            rows.put(item.featureKey(), item);
        }
        if (!items.isEmpty()) {
            events.publish(new ScopeFeatureDisplayPolicyUpdatedEvent(actor,
                    scope == FeatureDisplayScope.TEAM ? scopeId : null,
                    scope == FeatureDisplayScope.ORGANIZATION ? scopeId : null, metadata(scope, scopeId, items)));
        }
        // commit後のI/O失敗で保存済みなのに5xxとなる事態を避け、応答はTX内で組み立てる。
        return ScopeFeatureDisplayPolicyService.response(scope, rows);
    }

    private String metadata(FeatureDisplayScope scope, Long scopeId, List<FeatureDisplayPolicyItem> items) {
        try {
            return json.writeValueAsString(Map.of("scope_type", scope.name(), "scope_id", scopeId, "features", items));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("表示設定の監査情報を直列化できません", ex);
        }
    }
}
