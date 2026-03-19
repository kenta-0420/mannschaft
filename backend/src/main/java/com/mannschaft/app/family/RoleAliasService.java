package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.RoleAliasRequest;
import com.mannschaft.app.family.dto.RoleAliasResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * ロール呼称カスタマイズサービス。チームごとのロール表示名管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoleAliasService {

    private static final Set<String> ALLOWED_ROLES = Set.of("ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER");
    private static final Set<String> FORBIDDEN_ROLES = Set.of("SYSTEM_ADMIN", "GUEST");

    private final TeamRoleAliasRepository teamRoleAliasRepository;

    /**
     * チームのロール呼称一覧を取得する。
     *
     * @param teamId チームID
     * @return ロール呼称一覧
     */
    public ApiResponse<List<RoleAliasResponse>> getAliases(Long teamId) {
        List<TeamRoleAliasEntity> aliases = teamRoleAliasRepository.findByTeamId(teamId);
        List<RoleAliasResponse> responses = aliases.stream()
                .map(a -> new RoleAliasResponse(a.getRoleName(), a.getDisplayAlias()))
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * ロール呼称を一括設定する。
     *
     * @param teamId  チームID
     * @param userId  実行ユーザーID
     * @param request リクエスト
     * @return 更新後のロール呼称一覧
     */
    @Transactional
    public ApiResponse<List<RoleAliasResponse>> updateAliases(Long teamId, Long userId, RoleAliasRequest request) {
        for (RoleAliasRequest.AliasEntry entry : request.getAliases()) {
            String roleName = entry.getRoleName();

            if (FORBIDDEN_ROLES.contains(roleName)) {
                throw new BusinessException(FamilyErrorCode.FAMILY_003);
            }
            if (!ALLOWED_ROLES.contains(roleName)) {
                throw new BusinessException(FamilyErrorCode.FAMILY_024);
            }

            if (entry.getDisplayAlias().isEmpty()) {
                // 空文字列 → エイリアス削除（デフォルト名に戻す）
                teamRoleAliasRepository.deleteByTeamIdAndRoleName(teamId, roleName);
            } else {
                teamRoleAliasRepository.findByTeamIdAndRoleName(teamId, roleName)
                        .ifPresentOrElse(
                                existing -> existing.updateAlias(entry.getDisplayAlias(), userId),
                                () -> teamRoleAliasRepository.save(TeamRoleAliasEntity.builder()
                                        .teamId(teamId)
                                        .roleName(roleName)
                                        .displayAlias(entry.getDisplayAlias())
                                        .updatedBy(userId)
                                        .build())
                        );
            }
        }

        return getAliases(teamId);
    }
}
