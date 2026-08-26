package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamUniformSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ユニフォームセットリポジトリ（F08.7.1/05 §8.2）。
 *
 * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。</p>
 */
public interface TeamUniformSetRepository extends JpaRepository<TeamUniformSetEntity, UUID> {

    /** チームの有効なユニフォームセット一覧を取得する */
    List<TeamUniformSetEntity> findByTeamIdOrderByKindAscCreatedAtAsc(Long teamId);

    /** ID とチーム ID でユニフォームセットを取得する（IDOR 防止・他チームのセット参照は空で返る） */
    Optional<TeamUniformSetEntity> findByIdAndTeamId(UUID id, Long teamId);
}
