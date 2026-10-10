package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.reflection.entity.RecallSessionCommandEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

/** command も本人条件と current read を使う。 */
public interface RecallSessionCommandRepository extends JpaRepository<RecallSessionCommandEntity,UUID> {
    @Query(value="SELECT * FROM reflection_recall_commands WHERE user_id=:userId AND idempotency_key=:key FOR UPDATE",nativeQuery=true)
    Optional<RecallSessionCommandEntity> findOwnedForUpdate(@Param("userId")Long userId,@Param("key")UUID key);
    long deleteByUserId(Long userId);
}
