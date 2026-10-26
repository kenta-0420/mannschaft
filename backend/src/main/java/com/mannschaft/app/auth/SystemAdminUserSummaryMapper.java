package com.mannschaft.app.auth;

import com.mannschaft.app.auth.dto.SystemAdminUserSummaryResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import org.mapstruct.Mapper;

/**
 * システム管理ダッシュボード用のユーザーサマリ変換マッパー（auth ドメイン所有）。
 *
 * <p>{@link UserEntity} を、システム管理画面で必要な項目のみに絞った
 * {@link SystemAdminUserSummaryResponse} へ変換する。フィールド名を Entity と一致させているため
 * MapStruct が名前解決で自動マッピングし、DTO に存在しない項目（PII・内部フラグ等）は変換対象から
 * 自然に除外される。</p>
 *
 * <p><b>配置理由（ドメイン境界の原則）</b>: {@link UserEntity} を読む変換ロジックは auth ドメイン内に
 * 置く。admin ドメインへ置くと D-1 クロスドメイン Entity 参照違反となるため、admin コントローラーは
 * 本マッパーを注入して利用する。</p>
 */
@Mapper(componentModel = "spring")
public interface SystemAdminUserSummaryMapper {

    SystemAdminUserSummaryResponse toSummary(UserEntity entity);
}
