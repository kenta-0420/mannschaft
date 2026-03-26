package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.dto.ModerationTemplateResponse;
import com.mannschaft.app.moderation.entity.ModerationActionTemplateEntity;
import com.mannschaft.app.moderation.repository.ModerationActionTemplateRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ModerationTemplateService} の単体テスト。
 * テンプレートCRUDを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationTemplateService 単体テスト")
class ModerationTemplateServiceTest {

    @Mock
    private ModerationActionTemplateRepository templateRepository;

    @Mock
    private ModerationExtMapper mapper;

    @InjectMocks
    private ModerationTemplateService moderationTemplateService;

    private static final Long TEMPLATE_ID = 1L;
    private static final Long CREATED_BY = 100L;

    private ModerationActionTemplateEntity createTemplate() {
        return ModerationActionTemplateEntity.builder()
                .name("警告テンプレート")
                .actionType("WARNING")
                .reason("SPAM")
                .templateText("スパム行為が確認されました。")
                .language("ja")
                .isDefault(false)
                .createdBy(CREATED_BY)
                .build();
    }

    // ========================================
    // getAllTemplates
    // ========================================
    @Nested
    @DisplayName("getAllTemplates")
    class GetAllTemplates {

        @Test
        @DisplayName("正常系: テンプレート一覧を取得できる")
        void テンプレート一覧を取得できる() {
            // given
            List<ModerationActionTemplateEntity> entities = List.of(createTemplate());
            List<ModerationTemplateResponse> expected = List.of(
                    new ModerationTemplateResponse(TEMPLATE_ID, "警告テンプレート", "WARNING",
                            "SPAM", "テンプレート文", "ja", false, CREATED_BY, null, null));

            given(templateRepository.findAllByOrderByActionTypeAscNameAsc()).willReturn(entities);
            given(mapper.toTemplateResponseList(entities)).willReturn(expected);

            // when
            List<ModerationTemplateResponse> result = moderationTemplateService.getAllTemplates();

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // createTemplate
    // ========================================
    @Nested
    @DisplayName("createTemplate")
    class CreateTemplate {

        @Test
        @DisplayName("正常系: テンプレートを作成できる")
        void テンプレートを作成できる() {
            // given
            ModerationActionTemplateEntity saved = createTemplate();
            ModerationTemplateResponse expected = new ModerationTemplateResponse(TEMPLATE_ID,
                    "警告テンプレート", "WARNING", "SPAM", "テンプレート文", "ja", false, CREATED_BY, null, null);

            given(templateRepository.save(any(ModerationActionTemplateEntity.class))).willReturn(saved);
            given(mapper.toTemplateResponse(any(ModerationActionTemplateEntity.class))).willReturn(expected);

            // when
            ModerationTemplateResponse result = moderationTemplateService.createTemplate(
                    "警告テンプレート", "WARNING", "SPAM", "テンプレート文", "ja", false, CREATED_BY);

            // then
            assertThat(result).isEqualTo(expected);
            verify(templateRepository).save(any(ModerationActionTemplateEntity.class));
        }

        @Test
        @DisplayName("正常系: 言語省略時はjaがデフォルトで使用される")
        void 言語省略時はjaがデフォルトで使用される() {
            // given
            ModerationActionTemplateEntity saved = createTemplate();
            ModerationTemplateResponse expected = new ModerationTemplateResponse(TEMPLATE_ID,
                    "テスト", "WARNING", null, "テスト文", "ja", false, CREATED_BY, null, null);

            given(templateRepository.save(any(ModerationActionTemplateEntity.class))).willReturn(saved);
            given(mapper.toTemplateResponse(any(ModerationActionTemplateEntity.class))).willReturn(expected);

            // when
            moderationTemplateService.createTemplate("テスト", "WARNING", null, "テスト文", null, null, CREATED_BY);

            // then
            verify(templateRepository).save(any(ModerationActionTemplateEntity.class));
        }
    }

    // ========================================
    // updateTemplate
    // ========================================
    @Nested
    @DisplayName("updateTemplate")
    class UpdateTemplate {

        @Test
        @DisplayName("正常系: テンプレートを更新できる")
        void テンプレートを更新できる() {
            // given
            ModerationActionTemplateEntity entity = createTemplate();
            ModerationTemplateResponse expected = new ModerationTemplateResponse(TEMPLATE_ID,
                    "更新名", "WARNING", "SPAM", "更新文", "ja", true, CREATED_BY, null, null);

            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));
            given(templateRepository.save(any(ModerationActionTemplateEntity.class))).willReturn(entity);
            given(mapper.toTemplateResponse(any(ModerationActionTemplateEntity.class))).willReturn(expected);

            // when
            ModerationTemplateResponse result = moderationTemplateService.updateTemplate(
                    TEMPLATE_ID, "更新名", "WARNING", "SPAM", "更新文", "ja", true);

            // then
            assertThat(result.getName()).isEqualTo("更新名");
        }

        @Test
        @DisplayName("異常系: テンプレートが見つからない場合はエラー")
        void テンプレートが見つからない場合はエラー() {
            // given
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> moderationTemplateService.updateTemplate(
                    TEMPLATE_ID, "名前", "WARNING", null, "文", "ja", false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.TEMPLATE_NOT_FOUND));
        }
    }

    // ========================================
    // deleteTemplate
    // ========================================
    @Nested
    @DisplayName("deleteTemplate")
    class DeleteTemplate {

        @Test
        @DisplayName("正常系: テンプレートを論理削除できる")
        void テンプレートを論理削除できる() {
            // given
            ModerationActionTemplateEntity entity = createTemplate();
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(entity));
            given(templateRepository.save(any(ModerationActionTemplateEntity.class))).willReturn(entity);

            // when
            moderationTemplateService.deleteTemplate(TEMPLATE_ID);

            // then
            verify(templateRepository).save(any(ModerationActionTemplateEntity.class));
        }

        @Test
        @DisplayName("異常系: テンプレートが見つからない場合はエラー")
        void テンプレートが見つからない場合はエラー() {
            // given
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> moderationTemplateService.deleteTemplate(TEMPLATE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.TEMPLATE_NOT_FOUND));
        }
    }
}
