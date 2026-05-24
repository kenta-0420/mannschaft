package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VillageCategoryRepository extends JpaRepository<VillageCategoryEntity, UUID> {

    List<VillageCategoryEntity> findByParentIdIsNullAndDeletedAtIsNullOrderByDisplayOrderAsc();

    List<VillageCategoryEntity> findByParentIdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID parentId);

    List<VillageCategoryEntity> findAllByDeletedAtIsNullOrderByDisplayOrderAsc();

    Optional<VillageCategoryEntity> findByIdAndDeletedAtIsNull(UUID id);
}
