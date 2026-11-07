package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

/** 運営単一行は定数ID=1だけを扱う。 */
public interface RanchOperationalControlRepository extends JpaRepository<RanchOperationalControlEntity, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT control FROM RanchOperationalControlEntity control WHERE control.id = 1")
    Optional<RanchOperationalControlEntity> lockSingleton();
}
