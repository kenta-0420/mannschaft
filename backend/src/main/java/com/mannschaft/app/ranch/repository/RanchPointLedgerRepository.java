package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/** 本人の不変台帳を取得する。 */
public interface RanchPointLedgerRepository extends JpaRepository<RanchPointLedgerEntity, UUID> {
    List<RanchPointLedgerEntity> findByUserIdOrderByOccurredAtDescIdDesc(Long userId);
}
