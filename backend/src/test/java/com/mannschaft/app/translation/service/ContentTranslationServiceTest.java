package com.mannschaft.app.translation.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.translation.TranslationErrorCode;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.entity.TranslationConfigEntity;
import com.mannschaft.app.translation.repository.ContentTranslationQueryRepository;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
import com.mannschaft.app.translation.repository.TranslationConfigRepository;
import com.mannschaft.app.translation.service.ContentTranslationService.ChangeStatusRequest;
import com.mannschaft.app.translation.service.ContentTranslationService.ContentTranslationResponse;
import com.mannschaft.app.translation.service.ContentTranslationService.CreateTranslationRequest;
import com.mannschaft.app.translation.service.ContentTranslationService.TranslationDashboardResponse;
import com.mannschaft.app.translation.service.ContentTranslationService.TranslationSummaryResponse;
import com.mannschaft.app.translation.service.ContentTranslationService.UpdateTranslationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContentTranslationService 単体テスト")
class ContentTranslationServiceTest {

    @Mock
    private ContentTranslationRepository contentTranslationRepository;

    @Mock
    private ContentTranslationQueryRepository contentTranslationQueryRepository;

    @Mock
    private TranslationConfigRepository translationConfigRepository;

    @InjectMocks
    private ContentTranslationService sut;

    // ========================================
    // テストデータ生成ヘルパー
    // ========================================

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;
    private static final String SOURCE_TYPE = "BLOG_POST";
    private static final Long SOURCE_ID = 10L;
    private static final String LANGUAGE = "en";
    private static final Long USER_ID = 100L;
    private static final LocalDateTime SOURCE_UPDATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    private ContentTranslationEntity createEntity(String status) {
        ContentTranslationEntity entity = ContentTranslationEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .sourceType(SOURCE_TYPE).sourceId(SOURCE_ID)
                .language(LANGUAGE)
                .translatedTitle("Title").translatedBody("Body").translatedExcerpt("Summary")
                .status(status)
                .translatorId(USER_ID)
                .sourceUpdatedAt(SOURCE_UPDATED_AT)
                .build();
        ReflectionTestUtils.setField(entity, "id", 1L);
        ReflectionTestUtils.setField(entity, "version", 0L);
        return entity;
    }

    private TranslationConfigEntity createConfig(List<String> enabledLanguages) {
        return TranslationConfigEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .primaryLanguage("ja")
                .enabledLanguages(enabledLanguages)
                .build();
    }

    private CreateTranslationRequest createRequest() {
        CreateTranslationRequest req = new CreateTranslationRequest();
        req.setScopeType(SCOPE_TYPE);
        req.setScopeId(SCOPE_ID);
        req.setContentType(SOURCE_TYPE);
        req.setContentId(SOURCE_ID);
        req.setLanguage(LANGUAGE);
        req.setTitle("Title");
        req.setBody("Body");
        req.setSummary("Summary");
        req.setSourceUpdatedAt(SOURCE_UPDATED_AT);
        return req;
    }

    // ========================================
    // createTranslation
    // ========================================

    @Nested
    @DisplayName("createTranslation")
    class CreateTranslation {

        @Test
        @DisplayName("正常系_翻訳作成")
        void 正常系_翻訳作成() {
            // given
            TranslationConfigEntity config = createConfig(List.of("en", "ko"));
            given(translationConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(config));
            given(contentTranslationRepository.findBySourceTypeAndSourceIdAndLanguageAndDeletedAtIsNull(
                    SOURCE_TYPE, SOURCE_ID, LANGUAGE))
                    .willReturn(Optional.empty());

            ContentTranslationEntity saved = createEntity("DRAFT");
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willReturn(saved);

            CreateTranslationRequest req = createRequest();

            // when
            ApiResponse<ContentTranslationResponse> result = sut.createTranslation(USER_ID, req);

            // then
            assertThat(result.getData()).isNotNull();
            assertThat(result.getData().getLanguage()).isEqualTo(LANGUAGE);
            assertThat(result.getData().getStatus()).isEqualTo("DRAFT");
            assertThat(result.getData().getTitle()).isEqualTo("Title");
            verify(contentTranslationRepository).save(any(ContentTranslationEntity.class));
        }

        @Test
        @DisplayName("異常系_言語未有効化_設定なし_TRANSLATION_003")
        void 異常系_言語未有効化_設定なし_TRANSLATION_003() {
            // given
            given(translationConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            CreateTranslationRequest req = createRequest();

            // when & then
            assertThatThrownBy(() -> sut.createTranslation(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_003);
        }

        @Test
        @DisplayName("異常系_言語未有効化_有効言語に含まれない_TRANSLATION_003")
        void 異常系_言語未有効化_有効言語に含まれない_TRANSLATION_003() {
            // given
            TranslationConfigEntity config = createConfig(List.of("ko", "zh"));
            given(translationConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(config));

            CreateTranslationRequest req = createRequest(); // language = "en"

            // when & then
            assertThatThrownBy(() -> sut.createTranslation(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_003);
        }

        @Test
        @DisplayName("異常系_重複翻訳_TRANSLATION_004")
        void 異常系_重複翻訳_TRANSLATION_004() {
            // given
            TranslationConfigEntity config = createConfig(List.of("en", "ko"));
            given(translationConfigRepository.findByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(config));

            ContentTranslationEntity existing = createEntity("DRAFT");
            given(contentTranslationRepository.findBySourceTypeAndSourceIdAndLanguageAndDeletedAtIsNull(
                    SOURCE_TYPE, SOURCE_ID, LANGUAGE))
                    .willReturn(Optional.of(existing));

            CreateTranslationRequest req = createRequest();

            // when & then
            assertThatThrownBy(() -> sut.createTranslation(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_004);
        }
    }

    // ========================================
    // changeStatus
    // ========================================

    @Nested
    @DisplayName("changeStatus")
    class ChangeStatus {

        @Test
        @DisplayName("正常系_DRAFTからIN_REVIEWへ遷移")
        void 正常系_DRAFTからIN_REVIEWへ遷移() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findById(1L)).willReturn(Optional.of(entity));
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("IN_REVIEW");
            req.setVersion(0L);

            // when
            ApiResponse<ContentTranslationResponse> result = sut.changeStatus(1L, req);

            // then
            assertThat(result.getData().getStatus()).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("正常系_DRAFTからPUBLISHEDへ遷移_publishedAtが設定される")
        void 正常系_DRAFTからPUBLISHEDへ遷移() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findById(1L)).willReturn(Optional.of(entity));
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("PUBLISHED");
            req.setVersion(0L);

            // when
            ApiResponse<ContentTranslationResponse> result = sut.changeStatus(1L, req);

            // then
            assertThat(result.getData().getStatus()).isEqualTo("PUBLISHED");
            assertThat(result.getData().getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系_不正なステータス遷移_TRANSLATION_005")
        void 異常系_不正なステータス遷移_TRANSLATION_005() {
            // given: PUBLISHED → IN_REVIEW は許可されていない
            ContentTranslationEntity entity = createEntity("PUBLISHED");
            given(contentTranslationRepository.findById(1L)).willReturn(Optional.of(entity));

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("IN_REVIEW");
            req.setVersion(0L);

            // when & then
            assertThatThrownBy(() -> sut.changeStatus(1L, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_005);
        }

        @Test
        @DisplayName("異常系_バージョン不一致_TRANSLATION_007")
        void 異常系_バージョン不一致_TRANSLATION_007() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findById(1L)).willReturn(Optional.of(entity));

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("IN_REVIEW");
            req.setVersion(999L); // version mismatch

            // when & then
            assertThatThrownBy(() -> sut.changeStatus(1L, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_007);
        }
    }

    // ========================================
    // updateTranslation
    // ========================================

    @Nested
    @DisplayName("updateTranslation")
    class UpdateTranslation {

        @Test
        @DisplayName("正常系_翻訳内容を更新")
        void 正常系_翻訳内容を更新() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findById(1L)).willReturn(Optional.of(entity));
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            UpdateTranslationRequest req = new UpdateTranslationRequest();
            req.setTitle("New Title");
            req.setBody("New Body");
            req.setSummary("New Summary");
            req.setVersion(0L);

            // when
            ApiResponse<ContentTranslationResponse> result = sut.updateTranslation(1L, USER_ID, req);

            // then
            assertThat(result.getData().getTitle()).isEqualTo("New Title");
            assertThat(result.getData().getBody()).isEqualTo("New Body");
            assertThat(result.getData().getSummary()).isEqualTo("New Summary");
        }

        @Test
        @DisplayName("正常系_nullフィールドは既存値を維持")
        void 正常系_nullフィールドは既存値を維持() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findById(1L)).willReturn(Optional.of(entity));
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            UpdateTranslationRequest req = new UpdateTranslationRequest();
            req.setTitle(null);   // keep existing
            req.setBody(null);    // keep existing
            req.setSummary(null); // keep existing
            req.setVersion(0L);

            // when
            ApiResponse<ContentTranslationResponse> result = sut.updateTranslation(1L, USER_ID, req);

            // then
            assertThat(result.getData().getTitle()).isEqualTo("Title");
            assertThat(result.getData().getBody()).isEqualTo("Body");
            assertThat(result.getData().getSummary()).isEqualTo("Summary");
        }

        @Test
        @DisplayName("異常系_バージョン不一致_TRANSLATION_007")
        void 異常系_バージョン不一致_TRANSLATION_007() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findById(1L)).willReturn(Optional.of(entity));

            UpdateTranslationRequest req = new UpdateTranslationRequest();
            req.setTitle("New Title");
            req.setVersion(999L); // version mismatch

            // when & then
            assertThatThrownBy(() -> sut.updateTranslation(1L, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_007);
        }
    }

    // ========================================
    // getDashboard
    // ========================================

    @Nested
    @DisplayName("getDashboard")
    class GetDashboard {

        @Test
        @DisplayName("正常系_ダッシュボード統計を返す")
        void 正常系_ダッシュボード統計を返す() {
            // given
            Map<String, Long> statusCounts = Map.of(
                    "DRAFT", 5L,
                    "IN_REVIEW", 3L,
                    "PUBLISHED", 10L,
                    "NEEDS_UPDATE", 2L
            );
            given(contentTranslationQueryRepository.countByStatusGrouped(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(statusCounts);

            // when
            ApiResponse<TranslationDashboardResponse> result = sut.getDashboard(SCOPE_TYPE, SCOPE_ID);

            // then
            TranslationDashboardResponse dashboard = result.getData();
            assertThat(dashboard.getTotalTranslations()).isEqualTo(20L);
            assertThat(dashboard.getDraft()).isEqualTo(5L);
            assertThat(dashboard.getInReview()).isEqualTo(3L);
            assertThat(dashboard.getPublished()).isEqualTo(10L);
            assertThat(dashboard.getNeedsUpdate()).isEqualTo(2L);
        }
    }

    // ========================================
    // listTranslations
    // ========================================

    @Nested
    @DisplayName("listTranslations")
    class ListTranslations {

        @Test
        @DisplayName("正常系_ページネーション付き一覧を返す")
        void 正常系_ページネーション付き一覧を返す() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            Page<ContentTranslationEntity> page = new PageImpl<>(
                    List.of(entity), org.springframework.data.domain.PageRequest.of(0, 20), 1);

            given(contentTranslationRepository.findByScope(
                    eq(SCOPE_TYPE), eq(SCOPE_ID), eq(null), eq(null), eq(null), any(Pageable.class)))
                    .willReturn(page);

            // when
            PagedResponse<TranslationSummaryResponse> result =
                    sut.listTranslations(SCOPE_TYPE, SCOPE_ID, null, null, null, 0, 20);

            // then
            assertThat(result.getData()).hasSize(1);
            assertThat(result.getData().get(0).getLanguage()).isEqualTo(LANGUAGE);
            assertThat(result.getMeta().getTotal()).isEqualTo(1L);
            assertThat(result.getMeta().getPage()).isEqualTo(0);
            assertThat(result.getMeta().getSize()).isEqualTo(20);
            assertThat(result.getMeta().getTotalPages()).isEqualTo(1);
        }
    }

    // ========================================
    // deleteTranslation
    // ========================================

    @Nested
    @DisplayName("deleteTranslation")
    class DeleteTranslation {

        @Test
        @DisplayName("正常系_論理削除")
        void 正常系_論理削除() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findById(1L)).willReturn(Optional.of(entity));
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            sut.deleteTranslation(1L);

            // then
            verify(contentTranslationRepository).save(any(ContentTranslationEntity.class));
            // softDelete sets deletedAt
            assertThat(entity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系_対象が見つからない_TRANSLATION_002")
        void 異常系_対象が見つからない_TRANSLATION_002() {
            // given
            given(contentTranslationRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> sut.deleteTranslation(999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_002);
        }
    }
}
