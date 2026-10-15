package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageCategoryRequest;
import com.mannschaft.app.village.dto.VillageCategoryResponse;
import com.mannschaft.app.village.entity.VillageCategoryEntity;
import com.mannschaft.app.village.repository.VillageCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageCategoryService {

    private final VillageCategoryRepository categoryRepository;

    public List<VillageCategoryResponse> findAll() {
        List<VillageCategoryEntity> all = categoryRepository.findAllByDeletedAtIsNullOrderByDisplayOrderAsc();

        Map<UUID, List<VillageCategoryEntity>> byParent = all.stream()
                .filter(e -> e.getParentId() != null)
                .collect(Collectors.groupingBy(VillageCategoryEntity::getParentId));

        return all.stream()
                .filter(e -> e.getParentId() == null)
                .map(root -> toResponse(root, byParent))
                .toList();
    }

    @Transactional
    public VillageCategoryResponse create(VillageCategoryRequest request) {
        UUID parentId = parseUuid(request.parentId());
        VillageCategoryEntity entity = VillageCategoryEntity.builder()
                .name(request.name())
                .parentId(parentId)
                .displayOrder(request.displayOrder() != null ? request.displayOrder() : 0)
                .build();
        VillageCategoryEntity saved = categoryRepository.save(entity);
        log.info("村カテゴリ作成: id={}, name={}", saved.getId(), saved.getName());
        return toResponse(saved, Map.of());
    }

    @Transactional
    public VillageCategoryResponse update(UUID id, VillageCategoryRequest request) {
        VillageCategoryEntity entity = findOrThrow(id);
        UUID parentId = parseUuid(request.parentId());
        entity.update(request.name(), parentId, request.displayOrder());
        VillageCategoryEntity saved = categoryRepository.save(entity);
        log.info("村カテゴリ更新: id={}", id);
        return toResponse(saved, Map.of());
    }

    @Transactional
    public void delete(UUID id) {
        VillageCategoryEntity entity = findOrThrow(id);
        entity.softDelete();
        categoryRepository.save(entity);
        log.info("村カテゴリ削除: id={}", id);
    }

    private VillageCategoryEntity findOrThrow(UUID id) {
        return categoryRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
    }

    private VillageCategoryResponse toResponse(VillageCategoryEntity entity,
                                               Map<UUID, List<VillageCategoryEntity>> byParent) {
        List<VillageCategoryEntity> childEntities = byParent.getOrDefault(entity.getId(), new ArrayList<>());
        List<VillageCategoryResponse> children = childEntities.stream()
                .map(child -> toResponse(child, byParent))
                .toList();
        return new VillageCategoryResponse(
                entity.getId().toString(),
                entity.getName(),
                entity.getParentId() != null ? entity.getParentId().toString() : null,
                entity.getDisplayOrder(),
                children
        );
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(VillageErrorCode.VILLAGE_FIELD_INVALID);
        }
    }
}
