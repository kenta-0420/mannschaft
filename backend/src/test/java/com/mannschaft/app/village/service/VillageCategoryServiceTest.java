package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageCategoryRequest;
import com.mannschaft.app.village.dto.VillageCategoryResponse;
import com.mannschaft.app.village.entity.VillageCategoryEntity;
import com.mannschaft.app.village.repository.VillageCategoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("VillageCategoryService テスト")
class VillageCategoryServiceTest {

    @Mock
    private VillageCategoryRepository categoryRepository;

    @InjectMocks
    private VillageCategoryService villageCategoryService;

    // ─────────────────────────────────────────────────────────
    // findAll
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAll: ルートと子カテゴリをツリー構造で返す")
    void findAll_returnsTree() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        VillageCategoryEntity root = buildEntity(rootId, "スポーツ", null, 10);
        VillageCategoryEntity child = buildEntity(childId, "サッカー", rootId, 10);

        given(categoryRepository.findAllByDeletedAtIsNullOrderByDisplayOrderAsc())
                .willReturn(List.of(root, child));

        List<VillageCategoryResponse> result = villageCategoryService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("スポーツ");
        assertThat(result.get(0).children()).hasSize(1);
        assertThat(result.get(0).children().get(0).name()).isEqualTo("サッカー");
    }

    @Test
    @DisplayName("findAll: カテゴリが空の場合は空リストを返す")
    void findAll_emptyList() {
        given(categoryRepository.findAllByDeletedAtIsNullOrderByDisplayOrderAsc())
                .willReturn(List.of());

        List<VillageCategoryResponse> result = villageCategoryService.findAll();

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────────────────
    // create
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("create: parentId なしで正常作成できる")
    void create_withoutParent() {
        VillageCategoryRequest request = new VillageCategoryRequest("テクノロジー", null, 40);
        UUID newId = UUID.randomUUID();
        VillageCategoryEntity saved = buildEntity(newId, "テクノロジー", null, 40);

        given(categoryRepository.save(any())).willReturn(saved);

        VillageCategoryResponse response = villageCategoryService.create(request);

        assertThat(response.name()).isEqualTo("テクノロジー");
        assertThat(response.parentId()).isNull();
        assertThat(response.displayOrder()).isEqualTo(40);
        verify(categoryRepository).save(any());
    }

    @Test
    @DisplayName("create: parentId ありで子カテゴリを正常作成できる")
    void create_withParent() {
        UUID parentId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        VillageCategoryRequest request = new VillageCategoryRequest("サッカー", parentId.toString(), 10);
        VillageCategoryEntity saved = buildEntity(newId, "サッカー", parentId, 10);

        given(categoryRepository.save(any())).willReturn(saved);

        VillageCategoryResponse response = villageCategoryService.create(request);

        assertThat(response.name()).isEqualTo("サッカー");
        assertThat(response.parentId()).isEqualTo(parentId.toString());
        verify(categoryRepository).save(any());
    }

    @Test
    @DisplayName("create: parentId が UUID 形式でない場合は例外をスローする")
    void create_invalidParentIdFormat() {
        VillageCategoryRequest request = new VillageCategoryRequest("サッカー", "not-a-uuid", 10);

        assertThatThrownBy(() -> villageCategoryService.create(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(VillageErrorCode.VILLAGE_FIELD_INVALID));
    }

    // ─────────────────────────────────────────────────────────
    // update
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("update: 正常更新できる")
    void update_success() {
        UUID id = UUID.randomUUID();
        VillageCategoryEntity entity = buildEntity(id, "旧名称", null, 10);
        given(categoryRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.of(entity));

        VillageCategoryEntity updated = buildEntity(id, "新名称", null, 20);
        given(categoryRepository.save(any())).willReturn(updated);

        VillageCategoryRequest request = new VillageCategoryRequest("新名称", null, 20);
        VillageCategoryResponse response = villageCategoryService.update(id, request);

        assertThat(response.name()).isEqualTo("新名称");
        assertThat(response.displayOrder()).isEqualTo(20);
    }

    @Test
    @DisplayName("update: 存在しないIDで BusinessException をスローする")
    void update_notFound() {
        UUID id = UUID.randomUUID();
        given(categoryRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.empty());

        VillageCategoryRequest request = new VillageCategoryRequest("名称", null, 10);

        assertThatThrownBy(() -> villageCategoryService.update(id, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND));
    }

    // ─────────────────────────────────────────────────────────
    // delete
    // ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete: 論理削除で deletedAt がセットされる")
    void delete_softDelete() {
        UUID id = UUID.randomUUID();
        VillageCategoryEntity entity = buildEntity(id, "スポーツ", null, 10);
        given(categoryRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.of(entity));

        VillageCategoryEntity saved = buildEntityDeleted(id, "スポーツ");
        given(categoryRepository.save(any())).willReturn(saved);

        villageCategoryService.delete(id);

        verify(categoryRepository).save(any());
        assertThat(entity.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("delete: 存在しないIDで BusinessException をスローする")
    void delete_notFound() {
        UUID id = UUID.randomUUID();
        given(categoryRepository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> villageCategoryService.delete(id))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND));
    }

    // ─────────────────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────────────────

    private VillageCategoryEntity buildEntity(UUID id, String name, UUID parentId, int displayOrder) {
        VillageCategoryEntity entity = VillageCategoryEntity.builder()
                .name(name)
                .parentId(parentId)
                .displayOrder(displayOrder)
                .build();
        entity.setId(id);
        return entity;
    }

    private VillageCategoryEntity buildEntityDeleted(UUID id, String name) {
        VillageCategoryEntity entity = buildEntity(id, name, null, 0);
        entity.softDelete();
        return entity;
    }
}
