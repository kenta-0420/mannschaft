package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.reflection.entity.RecallSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

/** 本人条件を SQL に含め、書込は snapshot read に依存しない。 */
public interface RecallSessionRepository extends JpaRepository<RecallSessionEntity,UUID> {
    Optional<RecallSessionEntity> findByIdAndUserId(UUID id,Long userId);
    @Query(value="SELECT * FROM reflection_recall_sessions WHERE id=:id AND user_id=:userId FOR UPDATE",nativeQuery=true)
    Optional<RecallSessionEntity> findOwnedForUpdate(@Param("id")UUID id,@Param("userId")Long userId);
    long deleteByUserId(Long userId);
}
