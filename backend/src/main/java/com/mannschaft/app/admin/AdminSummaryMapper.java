package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.SystemAdminOrganizationSummaryResponse;
import com.mannschaft.app.admin.dto.SystemAdminTeamSummaryResponse;
import com.mannschaft.app.admin.dto.SystemAdminUserSummaryResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import org.mapstruct.Mapper;

/**
 * システム管理ダッシュボード一覧 API の Entity → サマリ DTO 変換マッパー。
 *
 * <p>組織・チーム・ユーザーの各 Entity を、システム管理画面で必要な項目のみに絞った
 * サマリ DTO へ変換する。フィールド名を Entity と一致させているため MapStruct が名前解決で
 * 自動マッピングし、DTO に存在しない項目（PII・内部フラグ等）は変換対象から自然に除外される。</p>
 */
@Mapper(componentModel = "spring")
public interface AdminSummaryMapper {

    SystemAdminUserSummaryResponse toUserSummary(UserEntity entity);

    SystemAdminOrganizationSummaryResponse toOrganizationSummary(OrganizationEntity entity);

    SystemAdminTeamSummaryResponse toTeamSummary(TeamEntity entity);
}
