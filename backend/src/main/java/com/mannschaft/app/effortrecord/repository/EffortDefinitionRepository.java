package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortDefinitionEntity;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F06.4.1 定義リポジトリ。PRESET は全員共通の参照データ。
 * TEAM 定義は source_key（{@code team:{teamId}:template:{templateId}}）で一意に引く（team_id 基準。組織では束ねない）。
 */
public interface EffortDefinitionRepository extends JpaRepository<EffortDefinitionEntity, UUID> {

    Optional<EffortDefinitionEntity> findBySourceTypeAndSourceKey(EffortSourceType sourceType, String sourceKey);

    /** 定義行を current read で行ロックする（revision 発行・指標設定更新の直列化）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM EffortDefinitionEntity d WHERE d.sourceType = :sourceType AND d.sourceKey = :sourceKey")
    Optional<EffortDefinitionEntity> findBySourceKeyForUpdate(@Param("sourceType") EffortSourceType sourceType,
                                                              @Param("sourceKey") String sourceKey);

    /** 標準プリセット（STUDY_V1 / EXERCISE_V1 / WEIGHT_V1）を source_key 昇順で取得する。 */
    List<EffortDefinitionEntity> findBySourceTypeOrderBySourceKeyAsc(EffortSourceType sourceType);
}
