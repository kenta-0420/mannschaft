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

    /**
     * 権限グループ（チーム・組織とも）に含められる権限の分類。{@code permissions.scope} は認可判定では参照されない分類列で、
     * TEAM と ORGANIZATION の両方で使う権限は主たる 1 値で登録されている（docs/security/README.md §4.3 ルール 3。
     * 例: 組織 DEPUTY_ADMIN への委任に使う MANAGE_CONTENT は TEAM 分類）。そのためチームでも組織でも
     * TEAM/ORGANIZATION 分類を合わせて対象とし、PLATFORM 分類（プラットフォーム運営の権限）は含めない。
     * 権限グループの作成・更新の検証（{@link PermissionGroupService}）と権限カタログはこの 1 つの判定を共用する。
     */
    public static final java.util.Set<PermissionEntity.Scope> GROUP_ASSIGNABLE_SCOPES =
            java.util.EnumSet.of(PermissionEntity.Scope.TEAM, PermissionEntity.Scope.ORGANIZATION);

    /** 権限グループに含められる権限か。 */
    public static boolean isGroupAssignable(PermissionEntity permission) {
        return GROUP_ASSIGNABLE_SCOPES.contains(permission.getScope());
    }

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
     * 指定スコープの権限グループに含められる権限を名前順で返す。チーム・組織のどちらで問い合わせても、
     * 作成・更新 API が受け付ける集合（{@link #GROUP_ASSIGNABLE_SCOPES}）と同じ。
     *
     * @param scopeType {@code TEAM} / {@code ORGANIZATION}
     */
    public List<PermissionCatalogItemResponse> list(String scopeType) {
        requireGroupScope(scopeType);
        return permissionRepository.findByScopeIn(GROUP_ASSIGNABLE_SCOPES).stream()
                .sorted(Comparator.comparing(PermissionEntity::getName))
                .map(p -> new PermissionCatalogItemResponse(
                        p.getId(), p.getName(), p.getDisplayName(), p.getScope().name(),
                        PermissionGroupService.ADMIN_ONLY_GRANTABLE_PERMISSIONS.contains(p.getName())))
                .toList();
    }
}
