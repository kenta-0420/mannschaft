package com.mannschaft.app.team;

import com.mannschaft.app.team.dto.SystemAdminTeamSummaryResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import org.mapstruct.Mapper;

/**
 * システム管理ダッシュボード用のチームサマリ変換マッパー（team ドメイン所有）。
 *
 * <p>{@link TeamEntity} を、システム管理画面で必要な項目のみに絞った
 * {@link SystemAdminTeamSummaryResponse} へ変換する。フィールド名を Entity と一致させているため
 * MapStruct が名前解決で自動マッピングし、DTO に存在しない項目（version・deletedAt 等）は変換対象から
 * 自然に除外される。</p>
 *
 * <p><b>配置理由（ドメイン境界の原則）</b>: {@link TeamEntity} を読む変換ロジックは team ドメイン内に
 * 置く。admin ドメインへ置くと D-1 クロスドメイン Entity 参照違反となるため、admin コントローラーは
 * 本マッパーを注入して利用する。</p>
 */
@Mapper(componentModel = "spring")
public interface SystemAdminTeamSummaryMapper {

    SystemAdminTeamSummaryResponse toSummary(TeamEntity entity);
}
