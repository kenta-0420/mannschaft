package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.ActionTemplateResponse;
import com.mannschaft.app.admin.dto.CreateActionTemplateRequest;
import com.mannschaft.app.admin.dto.UpdateActionTemplateRequest;
import com.mannschaft.app.admin.entity.AdminActionTemplateEntity;
import com.mannschaft.app.admin.repository.AdminActionTemplateRepository;
import com.mannschaft.app.admin.service.AdminActionTemplateService;
import com.mannschaft.app.common.BusinessException;
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
 * {@link AdminActionTemplateService} の単体テスト。
 * テンプレートのCRUD操作を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminActionTemplateService 単体テスト")
class AdminActionTemplateServiceTest {

    @Mock
    private AdminActionTemplateRepository templateRepository;

    @Mock
    private AnnouncementFeedbackMapper mapper;

    @InjectMocks
    private AdminActionTemplateService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEMPLATE_ID = 1L;
    private static final Long USER_ID = 100L;

    private AdminActionTemplateEntity createTemplateEntity() {
        return AdminActionTemplateEntity.builder()
                .name("警告テンプレート")
                .actionType("WARNING")
                .reason("規約違反")
                .templateText("あなたの行為は規約に違反しています。")
                .isDefault(false)
                .createdBy(USER_ID)
                .build();
    }

    private ActionTemplateResponse createTemplateResponse() {
        return new ActionTemplateResponse(
                TEMPLATE_ID, "警告テンプレート", "WARNING", "規約違反",
                "あなたの行為は規約に違反しています。", false, USER_ID, null, null);
    }

    // ========================================
    // getAllTemplates
    // ========================================

    @Nested
    @DisplayName("getAllTemplates")
    class GetAllTemplates {

        @Test
        @DisplayName("正常系: 全テンプレート一覧が返却される")
        void 取得_全件_一覧返却() {
            // Given
            List<AdminActionTemplateEntity> entities = List.of(createTemplateEntity());
            List<ActionTemplateResponse> responses = List.of(createTemplateResponse());
            given(templateRepository.findAllByOrderByActionTypeAscNameAsc()).willReturn(entities);
            given(mapper.toActionTemplateResponseList(entities)).willReturn(responses);

            // When
            List<ActionTemplateResponse> result = service.getAllTemplates();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("警告テンプレート");
            verify(templateRepository).findAllByOrderByActionTypeAscNameAsc();
        }
    }

    // ========================================
    // getTemplatesByActionType
    // ========================================

    @Nested
    @DisplayName("getTemplatesByActionType")
    class GetTemplatesByActionType {

        @Test
        @DisplayName("正常系: アクション種別でフィルタされたテンプレートが返却される")
        void 取得_種別指定_フィルタ結果返却() {
            // Given
            String actionType = "WARNING";
            List<AdminActionTemplateEntity> entities = List.of(createTemplateEntity());
            List<ActionTemplateResponse> responses = List.of(createTemplateResponse());
            given(templateRepository.findByActionTypeOrderByNameAsc(actionType)).willReturn(entities);
            given(mapper.toActionTemplateResponseList(entities)).willReturn(responses);

            // When
            List<ActionTemplateResponse> result = service.getTemplatesByActionType(actionType);

            // Then
            assertThat(result).hasSize(1);
            verify(templateRepository).findByActionTypeOrderByNameAsc(actionType);
        }
    }

    // ========================================
    // createTemplate
    // ========================================

    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("正常系: テンプレートが作成される")
        void 作成_正常_テンプレート保存() {
            // Given
            CreateActionTemplateRequest req = new CreateActionTemplateRequest(
                    "警告テンプレート", "WARNING", "規約違反",
                    "あなたの行為は規約に違反しています。", true);
            AdminActionTemplateEntity savedEntity = createTemplateEntity();
            ActionTemplateResponse response = createTemplateResponse();

            given(templateRepository.save(any(AdminActionTemplateEntity.class))).willReturn(savedEntity);
            given(mapper.toActionTemplateResponse(savedEntity)).willReturn(response);

            // When
            ActionTemplateResponse result = service.createTemplate(req, USER_ID);

            // Then
            assertThat(result.getName()).isEqualTo("警告テンプレート");
            verify(templateRepository).save(any(AdminActionTemplateEntity.class));
        }

        @Test
        @DisplayName("正常系: isDefaultがnullの場合falseがセットされる")
        void 作成_isDefaultNull_falseセット() {
            // Given
            CreateActionTemplateRequest req = new CreateActionTemplateRequest(
                    "テンプレート", "BAN", null, "テキスト", null);
            AdminActionTemplateEntity savedEntity = createTemplateEntity();
            ActionTemplateResponse response = createTemplateResponse();

            given(templateRepository.save(any(AdminActionTemplateEntity.class))).willReturn(savedEntity);
            given(mapper.toActionTemplateResponse(savedEntity)).willReturn(response);

            // When
            service.createTemplate(req, USER_ID);

            // Then
            verify(templateRepository).save(any(AdminActionTemplateEntity.class));
        }
    }

    // ========================================
    // updateTemplate
    // ========================================

    @Nested
    @DisplayName("updateTemplate")
    class UpdateTemplate {

        @Test
        @DisplayName("正常系: テンプレートが更新される")
        void 更新_正常_テンプレート保存() {
            // Given
            UpdateActionTemplateRequest req = new UpdateActionTemplateRequest(
                    "更新後テンプレート", "BAN", "重大違反", "更新テキスト", true);
            AdminActionTemplateEntity entity = createTemplateEntity();
            ActionTemplateResponse response = createTemplateResponse();

            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));
            given(templateRepository.save(entity)).willReturn(entity);
            given(mapper.toActionTemplateResponse(entity)).willReturn(response);

            // When
            ActionTemplateResponse result = service.updateTemplate(TEMPLATE_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(templateRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: テンプレート不在でADMIN_FB_007例外")
        void 更新_テンプレート不在_例外() {
            // Given
            UpdateActionTemplateRequest req = new UpdateActionTemplateRequest(
                    "テンプレート", "WARNING", null, "テキスト", null);
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateTemplate(TEMPLATE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_007"));
        }
    }

    // ========================================
    // deleteTemplate
    // ========================================

    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("正常系: テンプレートが論理削除される")
        void 削除_正常_論理削除() {
            // Given
            AdminActionTemplateEntity entity = createTemplateEntity();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));

            // When
            service.deleteTemplate(TEMPLATE_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(templateRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: テンプレート不在でADMIN_FB_007例外")
        void 削除_テンプレート不在_例外() {
            // Given
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteTemplate(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_007"));
        }
    }
}
