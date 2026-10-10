package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.BirthProfileCommandEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/** 本人の出生プロフィール命令だけを検索する窓口。 */
public interface BirthProfileCommandRepository extends JpaRepository<BirthProfileCommandEntity,UUID> {
    Optional<BirthProfileCommandEntity> findByUserIdAndCommandId(Long userId,UUID commandId);
    void deleteByUserId(Long userId);
}
