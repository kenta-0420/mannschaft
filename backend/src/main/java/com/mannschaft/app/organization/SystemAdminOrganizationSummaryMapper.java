package com.mannschaft.app.organization;

import com.mannschaft.app.organization.dto.SystemAdminOrganizationSummaryResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import org.mapstruct.Mapper;

/**
 * システム管理ダッシュボード用の組織サマリ変換マッパー（organization ドメイン所有）。
 *
 * <p>{@link OrganizationEntity} を、システム管理画面で必要な項目のみに絞った
 * {@link SystemAdminOrganizationSummaryResponse} へ変換する。フィールド名を Entity と一致させているため
 * MapStruct が名前解決で自動マッピングし、DTO に存在しない項目（version・deletedAt 等）は変換対象から
 * 自然に除外される。</p>
 *
 * <p><b>配置理由（ドメイン境界の原則）</b>: {@link OrganizationEntity} を読む変換ロジックは
 * organization ドメイン内に置く。admin ドメインへ置くと D-1 クロスドメイン Entity 参照違反となるため、
 * admin コントローラーは本マッパーを注入して利用する。</p>
 */
@Mapper(componentModel = "spring")
public interface SystemAdminOrganizationSummaryMapper {

    SystemAdminOrganizationSummaryResponse toSummary(OrganizationEntity entity);
}
