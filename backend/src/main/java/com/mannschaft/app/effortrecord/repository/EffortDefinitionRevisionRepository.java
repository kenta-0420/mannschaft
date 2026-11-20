package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortDefinitionRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** F06.4.1 定義 revision リポジトリ。revision は物理削除しない。 */
public interface EffortDefinitionRevisionRepository extends JpaRepository<EffortDefinitionRevisionEntity, UUID> {

    Optional<EffortDefinitionRevisionEntity> findByDefinitionIdAndRevision(UUID definitionId, Integer revision);

    List<EffortDefinitionRevisionEntity> findByDefinitionIdIn(Collection<UUID> definitionIds);

    /** 1 定義の指定 revision 群を一括で取得する（集計で原本が凍結した版をまとめて引く）。 */
    List<EffortDefinitionRevisionEntity> findByDefinitionIdAndRevisionIn(UUID definitionId, Collection<Integer> revisions);
}
