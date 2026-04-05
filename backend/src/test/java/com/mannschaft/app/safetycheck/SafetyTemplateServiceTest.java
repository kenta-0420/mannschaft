package com.mannschaft.app.safetycheck;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.safetycheck.dto.CreateTemplateRequest;
import com.mannschaft.app.safetycheck.dto.SafetyTemplateResponse;
import com.mannschaft.app.safetycheck.dto.UpdateTemplateRequest;
import com.mannschaft.app.safetycheck.entity.SafetyCheckTemplateEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckTemplateRepository;
import com.mannschaft.app.safetycheck.service.SafetyTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SafetyTemplateService} の単体テスト。
 * 安否確認テンプレートのCRUDを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SafetyTemplateService 単体テスト")
class SafetyTemplateServiceTest {

    @Mock
    private SafetyCheckTemplateRepository templateRepository;

    @Mock
    private SafetyCheckMapper mapper;

    @InjectMocks
    private SafetyTemplateService safetyTemplateService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEMPLATE_ID = 50L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;

    private SafetyCheckTemplateEntity createTemplateEntity() {
        return SafetyCheckTemplateEntity.builder()
                .scopeType(SafetyCheckScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .templateName("地震テンプレート")
                .title("地震発生")
                .message("安否を報告してください")
                .reminderIntervalMinutes(15)
                .sortOrder(1)
                .createdBy(USER_ID)
                .build();
    }

    private SafetyTemplateResponse createTemplateResponse() {
        return new SafetyTemplateResponse(
                TEMPLATE_ID, "TEAM", SCOPE_ID, "地震テンプレート",
                "地震発生", "安否を報告してください", 15,
                false, 1, USER_ID, LocalDateTime.now());
    }

    // ========================================
    // listTemplates
    // ========================================

    @Nested
    @DisplayName("listTemplates")
    class ListTemplates {

        @Test
        @DisplayName("テンプレート一覧取得_正常_リスト返却")
        void テンプレート一覧取得_正常_リスト返却() {
            // Given
            SafetyCheckTemplateEntity entity = createTemplateEntity();
            SafetyTemplateResponse response = createTemplateResponse();
            given(templateRepository.findAvailableTemplates(SafetyCheckScopeType.TEAM, SCOPE_ID))
                    .willReturn(List.of(entity));
            given(mapper.toTemplateResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<SafetyTemplateResponse> result = safetyTemplateService.listTemplates("TEAM", SCOPE_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTemplateName()).isEqualTo("地震テンプレート");
        }
    }

    // ========================================
    // getTemplate
    // ========================================

    @Nested
    @DisplayName("getTemplate")
    class GetTemplate {

        @Test
        @DisplayName("テンプレート詳細取得_正常_レスポンス返却")
        void テンプレート詳細取得_正常_レスポンス返却() {
            // Given
            SafetyCheckTemplateEntity entity = createTemplateEntity();
            SafetyTemplateResponse response = createTemplateResponse();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));
            given(mapper.toTemplateResponse(entity)).willReturn(response);

            // When
            SafetyTemplateResponse result = safetyTemplateService.getTemplate(TEMPLATE_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("地震発生");
        }

        @Test
        @DisplayName("テンプレート詳細取得_存在しない_BusinessException")
        void テンプレート詳細取得_存在しない_BusinessException() {
            // Given
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyTemplateService.getTemplate(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.TEMPLATE_NOT_FOUND));
        }
    }

    // ========================================
    // createTemplate
    // ========================================

    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("テンプレート作成_正常_レスポンス返却")
        void テンプレート作成_正常_レスポンス返却() {
            // Given
            CreateTemplateRequest req = new CreateTemplateRequest(
                    "新テンプレ", "タイトル", "メッセージ", 30, "TEAM", SCOPE_ID, 5);
            SafetyCheckTemplateEntity savedEntity = createTemplateEntity();
            SafetyTemplateResponse response = createTemplateResponse();
            given(templateRepository.save(any(SafetyCheckTemplateEntity.class))).willReturn(savedEntity);
            given(mapper.toTemplateResponse(savedEntity)).willReturn(response);

            // When
            SafetyTemplateResponse result = safetyTemplateService.createTemplate(req, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(templateRepository).save(any(SafetyCheckTemplateEntity.class));
        }

        @Test
        @DisplayName("テンプレート作成_scopeType未指定_正常")
        void テンプレート作成_scopeType未指定_正常() {
            // Given
            CreateTemplateRequest req = new CreateTemplateRequest(
                    "システムデフォルト", "タイトル", "メッセージ", null, null, null, null);
            SafetyCheckTemplateEntity savedEntity = createTemplateEntity();
            SafetyTemplateResponse response = createTemplateResponse();
            given(templateRepository.save(any(SafetyCheckTemplateEntity.class))).willReturn(savedEntity);
            given(mapper.toTemplateResponse(savedEntity)).willReturn(response);

            // When
            SafetyTemplateResponse result = safetyTemplateService.createTemplate(req, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // updateTemplate
    // ========================================

    @Nested
    @DisplayName("updateTemplate")
    class UpdateTemplate {

        @Test
        @DisplayName("テンプレート更新_正常_更新後レスポンス返却")
        void テンプレート更新_正常_更新後レスポンス返却() {
            // Given
            SafetyCheckTemplateEntity entity = createTemplateEntity();
            UpdateTemplateRequest req = new UpdateTemplateRequest(
                    "更新テンプレ", "更新タイトル", "更新メッセージ", 60, 2);
            SafetyTemplateResponse response = createTemplateResponse();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));
            given(templateRepository.save(entity)).willReturn(entity);
            given(mapper.toTemplateResponse(entity)).willReturn(response);

            // When
            safetyTemplateService.updateTemplate(TEMPLATE_ID, req);

            // Then
            assertThat(entity.getTemplateName()).isEqualTo("更新テンプレ");
            assertThat(entity.getTitle()).isEqualTo("更新タイトル");
            verify(templateRepository).save(entity);
        }

        @Test
        @DisplayName("テンプレート更新_存在しない_BusinessException")
        void テンプレート更新_存在しない_BusinessException() {
            // Given
            UpdateTemplateRequest req = new UpdateTemplateRequest("名前", null, null, null, null);
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyTemplateService.updateTemplate(TEMPLATE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.TEMPLATE_NOT_FOUND));
        }
    }

    // ========================================
    // deleteTemplate
    // ========================================

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("テンプレート削除_正常_deleteが呼ばれる")
        void テンプレート削除_正常_deleteが呼ばれる() {
            // Given
            SafetyCheckTemplateEntity entity = createTemplateEntity();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));

            // When
            safetyTemplateService.deleteTemplate(TEMPLATE_ID);

            // Then
            verify(templateRepository).delete(entity);
        }

        @Test
        @DisplayName("テンプレート削除_存在しない_BusinessException")
        void テンプレート削除_存在しない_BusinessException() {
            // Given
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyTemplateService.deleteTemplate(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // listAllTemplates
    // ========================================

    @Nested
    @DisplayName("listAllTemplates")
    class ListAllTemplates {

        @Test
        @DisplayName("全テンプレート一覧取得_正常_リスト返却")
        void 全テンプレート一覧取得_正常_リスト返却() {
            // Given
            SafetyCheckTemplateEntity entity = createTemplateEntity();
            SafetyTemplateResponse response = createTemplateResponse();
            given(templateRepository.findAllByOrderBySortOrderAsc())
                    .willReturn(List.of(entity));
            given(mapper.toTemplateResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<SafetyTemplateResponse> result = safetyTemplateService.listAllTemplates();

            // Then
            assertThat(result).hasSize(1);
        }
    }
}
