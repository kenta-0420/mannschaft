package com.mannschaft.app.template.repository;

import com.mannschaft.app.template.entity.TeamEnabledModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * チーム有効モジュールリポジトリ。
 */
public interface TeamEnabledModuleRepository extends JpaRepository<TeamEnabledModuleEntity, Long> {

    List<TeamEnabledModuleEntity> findByTeamId(Long teamId);

    Optional<TeamEnabledModuleEntity> findByTeamIdAndModuleId(Long teamId, Long moduleId);

    /**
     * 複数チームについて指定モジュールの有効化行を一括取得する（業務アラートの N+1 解消用）。
     * 呼び出し側は {@code teamIds} が空の場合に本メソッドを呼ばないこと。
     */
    List<TeamEnabledModuleEntity> findByTeamIdInAndModuleId(Collection<Long> teamIds, Long moduleId);

    /**
     * 有効なモジュール数を取得する（Analytics 集計用）。
     */
    long count();
}
