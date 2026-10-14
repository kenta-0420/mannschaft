package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.role.dto.PermissionCatalogItemResponse;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 権限カタログサービス。権限グループの作成・更新で選べる権限（{@code permissions} マスタ）を返す。
 * 認可（スコープの ADMIN+）は呼び出し元のコントローラーで行う。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PermissionCatalogService {

    private final PermissionRepository permissionRepository;

    /**
     * 権限グループのスコープ種別（TEAM / ORGANIZATION）を検証して返す。PLATFORM や未定義値は 400。
     */
    public static PermissionEntity.Scope requireGroupScope(String scopeType) {
        PermissionEntity.Scope scope = EnumInputParser.parse(PermissionEntity.Scope.class, scopeType, "scopeType");
        if (scope == PermissionEntity.Scope.PLATFORM) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        return scope;
    }

    /**
     * 指定スコープ向けの権限を名前順で返す。
     *
     * @param scopeType {@code TEAM} / {@code ORGANIZATION}
     */
    public List<PermissionCatalogItemResponse> list(String scopeType) {
        PermissionEntity.Scope scope = requireGroupScope(scopeType);
        return permissionRepository.findByScope(scope).stream()
                .sorted(Comparator.comparing(PermissionEntity::getName))
                .map(p -> new PermissionCatalogItemResponse(
                        p.getId(), p.getName(), p.getDisplayName(), p.getScope().name(),
                        PermissionGroupService.ADMIN_ONLY_GRANTABLE_PERMISSIONS.contains(p.getName())))
                .toList();
    }
}
