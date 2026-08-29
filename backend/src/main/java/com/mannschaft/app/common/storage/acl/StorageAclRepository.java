package com.mannschaft.app.common.storage.acl;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StorageAclRepository extends JpaRepository<StorageAclEntity, UUID> {

    Optional<StorageAclEntity> findByFileKey(String fileKey);
}
