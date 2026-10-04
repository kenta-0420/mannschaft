package com.mannschaft.app.ranch.repository;

import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** 運営単一行は定数ID=1だけを扱う。 */
public interface RanchOperationalControlRepository extends JpaRepository<RanchOperationalControlEntity, Integer> {
}
