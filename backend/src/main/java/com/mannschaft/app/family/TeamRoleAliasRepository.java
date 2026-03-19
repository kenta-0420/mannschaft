package com.mannschaft.app.family;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * チームロール呼称カスタマイズリポジトリ。
 */
public interface TeamRoleAliasRepository extends JpaRepository<TeamRoleAliasEntity, Long> {

    /**
     * チームのロール呼称一覧を取得する。
     */
    List<TeamRoleAliasEntity> findByTeamId(Long teamId);

    /**
     * チーム×ロールでエイリアスを取得する。
     */
    Optional<TeamRoleAliasEntity> findByTeamIdAndRoleName(Long teamId, String roleName);

    /**
     * チーム×ロールでエイリアスを削除する。
     */
    void deleteByTeamIdAndRoleName(Long teamId, String roleName);
}
