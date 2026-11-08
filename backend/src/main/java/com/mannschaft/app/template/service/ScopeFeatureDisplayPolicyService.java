package com.mannschaft.app.template.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.template.FeatureDisplayCatalog;
import com.mannschaft.app.template.FeatureDisplayScope;
import com.mannschaft.app.template.dto.FeatureDisplayPoliciesResponse;
import com.mannschaft.app.template.dto.FeatureDisplayPolicyItem;
import com.mannschaft.app.template.dto.UpdateFeatureDisplayPoliciesRequest;
import com.mannschaft.app.template.entity.ScopeFeatureDisplayPolicyEntity;
import com.mannschaft.app.template.repository.ScopeFeatureDisplayPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 認可をTX外で行う段取り役。一般業務APIには表示設定を適用しない。 */
@Service
@RequiredArgsConstructor
public class ScopeFeatureDisplayPolicyService {
    private final AccessControlService accessControlService;
    private final ScopeFeatureDisplayPolicyRepository repository;
    private final ScopeFeatureDisplayPolicyWriter writer;

    public FeatureDisplayPoliciesResponse getSettings(Long actor, FeatureDisplayScope scope, Long scopeId) {
        checkReadAccess(actor, scope, scopeId);
        return response(scope, snapshot(repository.findByScopeTypeAndScopeId(scope, scopeId)));
    }

    public FeatureDisplayPoliciesResponse updateSettings(Long actor, FeatureDisplayScope scope, Long scopeId,
                                                         UpdateFeatureDisplayPoliciesRequest request) {
        // 空更新も認可を必須にし、SYSTEM_ADMIN以外は当該scopeのADMINだけに限定する。
        if (!accessControlService.isSystemAdmin(actor) && !accessControlService.isAdmin(actor, scopeId, scope.name())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        validate(scope, request);
        return writer.applyUpdates(actor, scope, scopeId, request.features());
    }

    private void checkReadAccess(Long actor, FeatureDisplayScope scope, Long scopeId) {
        if (!accessControlService.isSystemAdmin(actor)
                && !accessControlService.hasRoleOrAbove(actor, scopeId, scope.name(), "SUPPORTER")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    private static void validate(FeatureDisplayScope scope, UpdateFeatureDisplayPoliciesRequest request) {
        if (request == null || request.features() == null) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        var keys = new HashSet<String>();
        for (FeatureDisplayPolicyItem item : request.features()) {
            if (item == null || item.featureKey() == null || item.memberVisible() == null || item.supporterVisible() == null
                    || !FeatureDisplayCatalog.keys(scope).contains(item.featureKey()) || !keys.add(item.featureKey())) {
                throw new BusinessException(CommonErrorCode.COMMON_001);
            }
        }
    }

    static Map<String, FeatureDisplayPolicyItem> snapshot(List<ScopeFeatureDisplayPolicyEntity> rows) {
        var result = new HashMap<String, FeatureDisplayPolicyItem>();
        for (var row : rows) {
            result.put(row.getFeatureKey(), new FeatureDisplayPolicyItem(row.getFeatureKey(), row.isMemberVisible(), row.isSupporterVisible()));
        }
        return result;
    }

    static FeatureDisplayPoliciesResponse response(FeatureDisplayScope scope, Map<String, FeatureDisplayPolicyItem> rows) {
        return new FeatureDisplayPoliciesResponse(FeatureDisplayCatalog.keys(scope).stream()
                .map(key -> rows.getOrDefault(key, new FeatureDisplayPolicyItem(key, true, true))).toList());
    }
}
