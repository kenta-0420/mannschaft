package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.knowledgebase.KnowledgeBaseErrorCode;
import com.mannschaft.app.knowledgebase.entity.KbTemplateEntity;
import com.mannschaft.app.knowledgebase.repository.KbTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link KbTemplateService} 単体テスト。
 *
 * <p>toBuilder → INSERT 化バグ根治の回帰テスト。
 * {@code updateTemplate} で {@code findById} の同一インスタンスが {@code save} に渡り
 * id を保持することを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KbTemplateService 単体テスト（toBuilder 根治 回帰）")
class KbTemplateServiceTest {

    private static final Long TEMPLATE_ID = 20L;
    private static final Long SCOPE_ID = 1L;
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long CREATED_BY = 100L;

    @Mock
    private KbTemplateRepository templateRepository;

    @InjectMocks
    private KbTemplateService service;

    private KbTemplateEntity templateWithId(Long id) {
        KbTemplateEntity template = KbTemplateEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("テンプレート名")
                .body("## 見出し\n本文")
                .icon("template-icon")
                .isSystem(false)
                .createdBy(CREATED_BY)
                .build();
        ReflectionTestUtils.setField(template, "id", id);
        ReflectionTestUtils.setField(template, "version", 0L);
        return template;
    }

    // ====================================================================
    // updateTemplate
    // ====================================================================

    @Nested
    @DisplayName("updateTemplate — toBuilder 根治 回帰")
    class UpdateTemplate {

        @Test
        @DisplayName("正常系: findById で取得した同一インスタンスが save に渡り id を保持する")
        void 正常系_同一インスタンスかつidを保持してsaveに渡る() {
            KbTemplateEntity template = templateWithId(TEMPLATE_ID);
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));
            given(templateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            KbTemplateService.UpdateKbTemplateRequest req =
                    new KbTemplateService.UpdateKbTemplateRequest("新テンプレート名", "新本文", "new-icon");
            service.updateTemplate(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID, req, 0L);

            ArgumentCaptor<KbTemplateEntity> captor = ArgumentCaptor.forClass(KbTemplateEntity.class);
            verify(templateRepository).save(captor.capture());

            KbTemplateEntity saved = captor.getValue();
            // 同一インスタンス（findById の managed entity そのもの）であることを確認
            assertThat(saved).isSameAs(template);
            // id 保持を明示確認（INSERT 化していない）
            assertThat(saved.getId()).isEqualTo(TEMPLATE_ID);
            // 更新内容の反映
            assertThat(saved.getName()).isEqualTo("新テンプレート名");
            assertThat(saved.getBody()).isEqualTo("新本文");
            assertThat(saved.getIcon()).isEqualTo("new-icon");
        }

        @Test
        @DisplayName("null フィールドは既存値を保持する")
        void null項目は既存値を維持する() {
            KbTemplateEntity template = templateWithId(TEMPLATE_ID);
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));
            given(templateRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // name のみ更新、body と icon は null（既存値維持）
            KbTemplateService.UpdateKbTemplateRequest req =
                    new KbTemplateService.UpdateKbTemplateRequest("名前だけ変更", null, null);
            service.updateTemplate(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID, req, 0L);

            ArgumentCaptor<KbTemplateEntity> captor = ArgumentCaptor.forClass(KbTemplateEntity.class);
            verify(templateRepository).save(captor.capture());

            KbTemplateEntity saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(TEMPLATE_ID);
            assertThat(saved.getName()).isEqualTo("名前だけ変更");
            // null を渡したフィールドは既存値が保持される
            assertThat(saved.getBody()).isEqualTo("## 見出し\n本文");
            assertThat(saved.getIcon()).isEqualTo("template-icon");
        }

        @Test
        @DisplayName("システムテンプレートは更新不可 → KB_011")
        void システムテンプレート_KB_011() {
            KbTemplateEntity systemTemplate = KbTemplateEntity.builder()
                    .scopeType("SYSTEM")
                    .scopeId(null)
                    .name("システムテンプレート")
                    .body("本文")
                    .icon(null)
                    .isSystem(true)
                    .createdBy(null)
                    .build();
            ReflectionTestUtils.setField(systemTemplate, "id", TEMPLATE_ID);
            ReflectionTestUtils.setField(systemTemplate, "version", 0L);

            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(systemTemplate));

            KbTemplateService.UpdateKbTemplateRequest req =
                    new KbTemplateService.UpdateKbTemplateRequest("変更試み", null, null);

            assertThatThrownBy(() -> service.updateTemplate(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID, req, 0L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(KnowledgeBaseErrorCode.KB_011);

            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("楽観的ロック version 不一致 → KB_006")
        void version不一致_KB_006() {
            KbTemplateEntity template = templateWithId(TEMPLATE_ID);
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));

            KbTemplateService.UpdateKbTemplateRequest req =
                    new KbTemplateService.UpdateKbTemplateRequest("新名前", null, null);

            assertThatThrownBy(() -> service.updateTemplate(TEMPLATE_ID, SCOPE_TYPE, SCOPE_ID, req, 99L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(KnowledgeBaseErrorCode.KB_006);

            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("別スコープのテンプレート → KB_010")
        void 別スコープ_KB_010() {
            KbTemplateEntity template = templateWithId(TEMPLATE_ID);
            // 異なるスコープで findById を通す（別スコープチェックで弾く）
            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));

            KbTemplateService.UpdateKbTemplateRequest req =
                    new KbTemplateService.UpdateKbTemplateRequest("新名前", null, null);

            // 別の scopeType でリクエスト
            assertThatThrownBy(
                    () -> service.updateTemplate(TEMPLATE_ID, "ORGANIZATION", 999L, req, 0L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(KnowledgeBaseErrorCode.KB_010);
        }
    }
}
