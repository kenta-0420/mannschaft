package com.mannschaft.app.translation.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.entity.TranslationAssignmentEntity;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
import com.mannschaft.app.translation.repository.TranslationAssignmentRepository;
import com.mannschaft.app.translation.service.TranslationAssignmentService.AssignTranslatorRequest;
import com.mannschaft.app.translation.service.TranslationAssignmentService.TranslationAssignmentResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("TranslationAssignmentService 単体テスト")
class TranslationAssignmentServiceTest {

    @Mock
    private TranslationAssignmentRepository translationAssignmentRepository;

    @Mock
    private ContentTranslationRepository contentTranslationRepository;

    @InjectMocks
    private TranslationAssignmentService service;

    private static final Long TRANSLATION_ID = 1L;
    private static final Long ASSIGNEE_ID = 100L;
    private static final Long ASSIGNED_BY = 200L;
    private static final Long ASSIGNMENT_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 50L;
    private static final String LANGUAGE = "ja";

    // ========================================
    // ヘルパーメソッド
    // ========================================

    private ContentTranslationEntity createTranslation() {
        ContentTranslationEntity entity = ContentTranslationEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .sourceType("BLOG_POST")
                .sourceId(1L)
                .language(LANGUAGE)
                .sourceUpdatedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(entity, "id", TRANSLATION_ID);
        ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.now());
        return entity;
    }

    private TranslationAssignmentEntity createAssignment(Long id) {
        TranslationAssignmentEntity entity = TranslationAssignmentEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .userId(ASSIGNEE_ID)
                .language(LANGUAGE)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.now());
        return entity;
    }

    private AssignTranslatorRequest createRequest() {
        AssignTranslatorRequest req = new AssignTranslatorRequest();
        req.setTranslationId(TRANSLATION_ID);
        req.setAssigneeId(ASSIGNEE_ID);
        return req;
    }

    // ========================================
    // assignTranslator
    // ========================================

    @Nested
    @DisplayName("assignTranslator")
    class AssignTranslator {

        @Test
        @DisplayName("正常系: 新規アサインを作成して返す")
        void 新規アサイン作成() {
            // Arrange
            ContentTranslationEntity translation = createTranslation();
            TranslationAssignmentEntity saved = createAssignment(ASSIGNMENT_ID);
            AssignTranslatorRequest req = createRequest();

            given(contentTranslationRepository.findById(TRANSLATION_ID))
                    .willReturn(Optional.of(translation));
            given(translationAssignmentRepository
                    .existsByScopeTypeAndScopeIdAndUserIdAndLanguageAndIsActiveTrue(
                            SCOPE_TYPE, SCOPE_ID, ASSIGNEE_ID, LANGUAGE))
                    .willReturn(false);
            given(translationAssignmentRepository.save(any(TranslationAssignmentEntity.class)))
                    .willReturn(saved);

            // Act
            ApiResponse<TranslationAssignmentResponse> response =
                    service.assignTranslator(ASSIGNED_BY, req);

            // Assert
            assertThat(response.getData()).isNotNull();
            assertThat(response.getData().getId()).isEqualTo(ASSIGNMENT_ID);
            assertThat(response.getData().getScopeType()).isEqualTo(SCOPE_TYPE);
            assertThat(response.getData().getScopeId()).isEqualTo(SCOPE_ID);
            assertThat(response.getData().getAssigneeId()).isEqualTo(ASSIGNEE_ID);
            assertThat(response.getData().getLanguage()).isEqualTo(LANGUAGE);
            assertThat(response.getData().isActive()).isTrue();
            then(translationAssignmentRepository).should().save(any(TranslationAssignmentEntity.class));
        }

        @Test
        @DisplayName("正常系: 同一スコープxユーザーx言語の既存アサインを返す（冪等）")
        void 冪等_既存アサイン返却() {
            // Arrange
            ContentTranslationEntity translation = createTranslation();
            TranslationAssignmentEntity existing = createAssignment(ASSIGNMENT_ID);
            AssignTranslatorRequest req = createRequest();

            given(contentTranslationRepository.findById(TRANSLATION_ID))
                    .willReturn(Optional.of(translation));
            given(translationAssignmentRepository
                    .existsByScopeTypeAndScopeIdAndUserIdAndLanguageAndIsActiveTrue(
                            SCOPE_TYPE, SCOPE_ID, ASSIGNEE_ID, LANGUAGE))
                    .willReturn(true);
            given(translationAssignmentRepository
                    .findByScopeTypeAndScopeIdAndUserIdAndIsActiveTrue(
                            SCOPE_TYPE, SCOPE_ID, ASSIGNEE_ID))
                    .willReturn(List.of(existing));

            // Act
            ApiResponse<TranslationAssignmentResponse> response =
                    service.assignTranslator(ASSIGNED_BY, req);

            // Assert
            assertThat(response.getData()).isNotNull();
            assertThat(response.getData().getId()).isEqualTo(ASSIGNMENT_ID);
            assertThat(response.getData().getLanguage()).isEqualTo(LANGUAGE);
            then(translationAssignmentRepository).shouldHaveNoMoreInteractions();
        }

        @Test
        @DisplayName("異常系: 翻訳コンテンツが見つからずTRANSLATION_002例外")
        void 翻訳コンテンツ未存在_例外() {
            // Arrange
            AssignTranslatorRequest req = createRequest();
            given(contentTranslationRepository.findById(TRANSLATION_ID))
                    .willReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.assignTranslator(ASSIGNED_BY, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TRANSLATION_002"));
        }
    }

    // ========================================
    // listAssignments
    // ========================================

    @Nested
    @DisplayName("listAssignments")
    class ListAssignments {

        @Test
        @DisplayName("正常系: スコープに紐づくアサイン一覧を返す")
        void アサイン一覧取得() {
            // Arrange
            ContentTranslationEntity translation = createTranslation();
            TranslationAssignmentEntity assignment1 = createAssignment(1L);
            TranslationAssignmentEntity assignment2 = createAssignment(2L);
            ReflectionTestUtils.setField(assignment2, "language", "en");

            given(contentTranslationRepository.findById(TRANSLATION_ID))
                    .willReturn(Optional.of(translation));
            given(translationAssignmentRepository
                    .findByScopeTypeAndScopeIdAndIsActiveTrue(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of(assignment1, assignment2));

            // Act
            ApiResponse<List<TranslationAssignmentResponse>> response =
                    service.listAssignments(TRANSLATION_ID);

            // Assert
            assertThat(response.getData()).hasSize(2);
            assertThat(response.getData().get(0).getId()).isEqualTo(1L);
            assertThat(response.getData().get(1).getId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("異常系: 翻訳コンテンツが見つからずTRANSLATION_002例外")
        void 翻訳コンテンツ未存在_例外() {
            // Arrange
            given(contentTranslationRepository.findById(TRANSLATION_ID))
                    .willReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> service.listAssignments(TRANSLATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TRANSLATION_002"));
        }
    }

    // ========================================
    // listMyAssignments
    // ========================================

    @Nested
    @DisplayName("listMyAssignments")
    class ListMyAssignments {

        @Test
        @DisplayName("正常系: 自分のアサイン一覧を返す")
        void 自分のアサイン一覧取得() {
            // Arrange
            TranslationAssignmentEntity assignment = createAssignment(ASSIGNMENT_ID);

            given(translationAssignmentRepository
                    .findByScopeTypeAndScopeIdAndUserIdAndIsActiveTrue(
                            SCOPE_TYPE, SCOPE_ID, ASSIGNEE_ID))
                    .willReturn(List.of(assignment));

            // Act
            ApiResponse<List<TranslationAssignmentResponse>> response =
                    service.listMyAssignments(SCOPE_TYPE, SCOPE_ID, ASSIGNEE_ID);

            // Assert
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getId()).isEqualTo(ASSIGNMENT_ID);
            assertThat(response.getData().get(0).getAssigneeId()).isEqualTo(ASSIGNEE_ID);
            assertThat(response.getData().get(0).getLanguage()).isEqualTo(LANGUAGE);
        }
    }

    // ========================================
    // removeAssignment
    // ========================================

    @Nested
    @DisplayName("removeAssignment")
    class RemoveAssignment {

        @Test
        @DisplayName("正常系: アサインを物理削除する")
        void アサイン削除() {
            // Arrange
            given(translationAssignmentRepository.existsById(ASSIGNMENT_ID))
                    .willReturn(true);

            // Act
            service.removeAssignment(ASSIGNMENT_ID);

            // Assert
            then(translationAssignmentRepository).should().deleteById(ASSIGNMENT_ID);
        }

        @Test
        @DisplayName("異常系: アサインが見つからずTRANSLATION_009例外")
        void アサイン未存在_例外() {
            // Arrange
            given(translationAssignmentRepository.existsById(ASSIGNMENT_ID))
                    .willReturn(false);

            // Act & Assert
            assertThatThrownBy(() -> service.removeAssignment(ASSIGNMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TRANSLATION_009"));
        }
    }
}
