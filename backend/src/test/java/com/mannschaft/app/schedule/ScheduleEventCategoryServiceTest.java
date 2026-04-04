package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import com.mannschaft.app.schedule.service.ScheduleEventCategoryService;
import com.mannschaft.app.schedule.service.ScheduleEventCategoryService.CreateCategoryData;
import com.mannschaft.app.schedule.service.ScheduleEventCategoryService.UpdateCategoryData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleEventCategoryService} の単体テスト。
 * カテゴリCRUD・プリセット初期化・スコープ検証を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleEventCategoryService 単体テスト")
class ScheduleEventCategoryServiceTest {

    @Mock
    private ScheduleEventCategoryRepository categoryRepository;

    @InjectMocks
    private ScheduleEventCategoryService categoryService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long ORG_ID = 2L;
    private static final Long CATEGORY_ID = 10L;

    private ScheduleEventCategoryEntity createTeamCategory() {
        return ScheduleEventCategoryEntity.builder()
                .teamId(TEAM_ID)
                .name("式典")
                .color("#EF4444")
                .isDayOffCategory(false)
                .sortOrder(1)
                .build();
    }

    private ScheduleEventCategoryEntity createOrgCategory() {
        return ScheduleEventCategoryEntity.builder()
                .organizationId(ORG_ID)
                .name("全社行事")
                .color("#3B82F6")
                .isDayOffCategory(false)
                .sortOrder(1)
                .build();
    }

    // ========================================
    // getCategoriesForTeam
    // ========================================

    @Nested
    @DisplayName("getCategoriesForTeam")
    class GetCategoriesForTeam {

        @Test
        @DisplayName("カテゴリ取得_正常_チームと組織のカテゴリをマージして返す")
        void カテゴリ取得_正常_チームと組織のカテゴリをマージして返す() {
            // given
            ScheduleEventCategoryEntity teamCat = createTeamCategory();
            ScheduleEventCategoryEntity orgCat = createOrgCategory();
            given(categoryRepository.findByTeamIdOrderBySortOrder(TEAM_ID))
                    .willReturn(List.of(teamCat));
            given(categoryRepository.findByOrganizationIdOrderBySortOrder(ORG_ID))
                    .willReturn(List.of(orgCat));

            // when
            List<ScheduleEventCategoryEntity> result = categoryService.getCategoriesForTeam(TEAM_ID, ORG_ID);

            // then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("全社行事");
            assertThat(result.get(1).getName()).isEqualTo("式典");
        }
    }

    // ========================================
    // getCategoriesForOrganization
    // ========================================

    @Nested
    @DisplayName("getCategoriesForOrganization")
    class GetCategoriesForOrganization {

        @Test
        @DisplayName("組織カテゴリ取得_正常_カテゴリ一覧を返す")
        void 組織カテゴリ取得_正常_カテゴリ一覧を返す() {
            // given
            given(categoryRepository.findByOrganizationIdOrderBySortOrder(ORG_ID))
                    .willReturn(List.of(createOrgCategory()));

            // when
            List<ScheduleEventCategoryEntity> result = categoryService.getCategoriesForOrganization(ORG_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getById
    // ========================================

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("カテゴリ取得_存在_エンティティを返す")
        void カテゴリ取得_存在_エンティティを返す() {
            // given
            ScheduleEventCategoryEntity entity = createTeamCategory();
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(entity));

            // when
            ScheduleEventCategoryEntity result = categoryService.getById(CATEGORY_ID);

            // then
            assertThat(result.getName()).isEqualTo("式典");
        }

        @Test
        @DisplayName("カテゴリ取得_不存在_例外スロー")
        void カテゴリ取得_不存在_例外スロー() {
            // given
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> categoryService.getById(CATEGORY_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleEventCategoryErrorCode.CATEGORY_NOT_FOUND);
        }
    }

    // ========================================
    // createTeamCategory
    // ========================================

    @Nested
    @DisplayName("createTeamCategory")
    class CreateTeamCategory {

        @Test
        @DisplayName("チームカテゴリ作成_正常_保存されて返される")
        void チームカテゴリ作成_正常_保存されて返される() {
            // given
            given(categoryRepository.existsByTeamIdAndName(TEAM_ID, "新カテゴリ")).willReturn(false);
            given(categoryRepository.findByTeamIdOrderBySortOrder(TEAM_ID)).willReturn(List.of());
            given(categoryRepository.save(any(ScheduleEventCategoryEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            CreateCategoryData data = new CreateCategoryData("新カテゴリ", "#FF0000", null, false, 1);

            // when
            ScheduleEventCategoryEntity result = categoryService.createTeamCategory(TEAM_ID, data);

            // then
            assertThat(result.getName()).isEqualTo("新カテゴリ");
            assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
            verify(categoryRepository).save(any(ScheduleEventCategoryEntity.class));
        }

        @Test
        @DisplayName("チームカテゴリ作成_名前重複_例外スロー")
        void チームカテゴリ作成_名前重複_例外スロー() {
            // given
            given(categoryRepository.existsByTeamIdAndName(TEAM_ID, "式典")).willReturn(true);

            CreateCategoryData data = new CreateCategoryData("式典", "#FF0000", null, false, 1);

            // when & then
            assertThatThrownBy(() -> categoryService.createTeamCategory(TEAM_ID, data))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleEventCategoryErrorCode.DUPLICATE_CATEGORY_NAME);
        }

        @Test
        @DisplayName("チームカテゴリ作成_上限超過_例外スロー")
        void チームカテゴリ作成_上限超過_例外スロー() {
            // given
            given(categoryRepository.existsByTeamIdAndName(TEAM_ID, "新カテゴリ")).willReturn(false);
            List<ScheduleEventCategoryEntity> thirtyCategories =
                    java.util.stream.IntStream.range(0, 30)
                            .mapToObj(i -> createTeamCategory())
                            .toList();
            given(categoryRepository.findByTeamIdOrderBySortOrder(TEAM_ID)).willReturn(thirtyCategories);

            CreateCategoryData data = new CreateCategoryData("新カテゴリ", "#FF0000", null, false, 31);

            // when & then
            assertThatThrownBy(() -> categoryService.createTeamCategory(TEAM_ID, data))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleEventCategoryErrorCode.CATEGORY_LIMIT_EXCEEDED);
        }
    }

    // ========================================
    // createOrgCategory
    // ========================================

    @Nested
    @DisplayName("createOrgCategory")
    class CreateOrgCategory {

        @Test
        @DisplayName("組織カテゴリ作成_正常_保存されて返される")
        void 組織カテゴリ作成_正常_保存されて返される() {
            // given
            given(categoryRepository.existsByOrganizationIdAndName(ORG_ID, "研修")).willReturn(false);
            given(categoryRepository.findByOrganizationIdOrderBySortOrder(ORG_ID)).willReturn(List.of());
            given(categoryRepository.save(any(ScheduleEventCategoryEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            CreateCategoryData data = new CreateCategoryData("研修", "#8B5CF6", null, false, 1);

            // when
            ScheduleEventCategoryEntity result = categoryService.createOrgCategory(ORG_ID, data);

            // then
            assertThat(result.getName()).isEqualTo("研修");
            assertThat(result.getOrganizationId()).isEqualTo(ORG_ID);
        }
    }

    // ========================================
    // updateCategory
    // ========================================

    @Nested
    @DisplayName("updateCategory")
    class UpdateCategory {

        @Test
        @DisplayName("カテゴリ更新_名前変更_重複チェック後に更新される")
        void カテゴリ更新_名前変更_重複チェック後に更新される() {
            // given
            ScheduleEventCategoryEntity existing = createTeamCategory();
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(existing));
            given(categoryRepository.existsByTeamIdAndName(TEAM_ID, "新名前")).willReturn(false);
            given(categoryRepository.save(any(ScheduleEventCategoryEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            UpdateCategoryData data = new UpdateCategoryData("新名前", null, null, null, null);

            // when
            ScheduleEventCategoryEntity result = categoryService.updateCategory(CATEGORY_ID, data);

            // then
            assertThat(result.getName()).isEqualTo("新名前");
        }

        @Test
        @DisplayName("カテゴリ更新_名前同じ_重複チェックスキップ")
        void カテゴリ更新_名前同じ_重複チェックスキップ() {
            // given
            ScheduleEventCategoryEntity existing = createTeamCategory();
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(existing));
            given(categoryRepository.save(any(ScheduleEventCategoryEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            UpdateCategoryData data = new UpdateCategoryData("式典", "#00FF00", null, null, null);

            // when
            ScheduleEventCategoryEntity result = categoryService.updateCategory(CATEGORY_ID, data);

            // then
            assertThat(result.getColor()).isEqualTo("#00FF00");
        }

        @Test
        @DisplayName("カテゴリ更新_不存在_例外スロー")
        void カテゴリ更新_不存在_例外スロー() {
            // given
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.empty());

            UpdateCategoryData data = new UpdateCategoryData("新名前", null, null, null, null);

            // when & then
            assertThatThrownBy(() -> categoryService.updateCategory(CATEGORY_ID, data))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleEventCategoryErrorCode.CATEGORY_NOT_FOUND);
        }
    }

    // ========================================
    // deleteCategory
    // ========================================

    @Nested
    @DisplayName("deleteCategory")
    class DeleteCategory {

        @Test
        @DisplayName("カテゴリ削除_正常_削除される")
        void カテゴリ削除_正常_削除される() {
            // given
            ScheduleEventCategoryEntity existing = createTeamCategory();
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(existing));

            // when
            categoryService.deleteCategory(CATEGORY_ID);

            // then
            verify(categoryRepository).delete(existing);
        }

        @Test
        @DisplayName("カテゴリ削除_不存在_例外スロー")
        void カテゴリ削除_不存在_例外スロー() {
            // given
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> categoryService.deleteCategory(CATEGORY_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleEventCategoryErrorCode.CATEGORY_NOT_FOUND);
        }
    }

    // ========================================
    // initializePresets
    // ========================================

    @Nested
    @DisplayName("initializePresets")
    class InitializePresets {

        @Test
        @DisplayName("プリセット初期化_学校テンプレート_7件保存される")
        void プリセット初期化_学校テンプレート_7件保存される() {
            // given
            given(categoryRepository.save(any(ScheduleEventCategoryEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            categoryService.initializePresets(TEAM_ID, true, "クラス");

            // then
            verify(categoryRepository, org.mockito.Mockito.times(7))
                    .save(any(ScheduleEventCategoryEntity.class));
        }

        @Test
        @DisplayName("プリセット初期化_未定義テンプレート_保存されない")
        void プリセット初期化_未定義テンプレート_保存されない() {
            // when
            categoryService.initializePresets(TEAM_ID, true, "未定義");

            // then
            verify(categoryRepository, org.mockito.Mockito.never())
                    .save(any(ScheduleEventCategoryEntity.class));
        }

        @Test
        @DisplayName("プリセット初期化_組織スコープ_organizationIdが設定される")
        void プリセット初期化_組織スコープ_organizationIdが設定される() {
            // given
            given(categoryRepository.save(any(ScheduleEventCategoryEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            categoryService.initializePresets(ORG_ID, false, "クラブ・サークル");

            // then
            verify(categoryRepository, org.mockito.Mockito.times(4))
                    .save(any(ScheduleEventCategoryEntity.class));
        }
    }

    // ========================================
    // validateCategoryScope
    // ========================================

    @Nested
    @DisplayName("validateCategoryScope")
    class ValidateCategoryScope {

        @Test
        @DisplayName("スコープ検証_categoryIdがnull_例外なし")
        void スコープ検証_categoryIdがnull_例外なし() {
            // when & then（例外なしで正常終了）
            categoryService.validateCategoryScope(TEAM_ID, ORG_ID, null);
        }

        @Test
        @DisplayName("スコープ検証_チームスコープ一致_例外なし")
        void スコープ検証_チームスコープ一致_例外なし() {
            // given
            ScheduleEventCategoryEntity category = createTeamCategory();
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(category));

            // when & then（例外なしで正常終了）
            categoryService.validateCategoryScope(TEAM_ID, ORG_ID, CATEGORY_ID);
        }

        @Test
        @DisplayName("スコープ検証_チームスコープ不一致_例外スロー")
        void スコープ検証_チームスコープ不一致_例外スロー() {
            // given
            ScheduleEventCategoryEntity category = ScheduleEventCategoryEntity.builder()
                    .teamId(999L)
                    .name("他チームカテゴリ")
                    .build();
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(category));

            // when & then
            assertThatThrownBy(() -> categoryService.validateCategoryScope(TEAM_ID, ORG_ID, CATEGORY_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleEventCategoryErrorCode.CATEGORY_SCOPE_MISMATCH);
        }

        @Test
        @DisplayName("スコープ検証_組織スコープ不一致_例外スロー")
        void スコープ検証_組織スコープ不一致_例外スロー() {
            // given
            ScheduleEventCategoryEntity category = ScheduleEventCategoryEntity.builder()
                    .organizationId(999L)
                    .name("他組織カテゴリ")
                    .build();
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(category));

            // when & then
            assertThatThrownBy(() -> categoryService.validateCategoryScope(null, ORG_ID, CATEGORY_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleEventCategoryErrorCode.CATEGORY_SCOPE_MISMATCH);
        }
    }
}
