package com.mannschaft.app.directmail.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.directmail.DirectMailMapper;
import com.mannschaft.app.directmail.repository.DirectMailTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * {@link DirectMailTemplateService#listTemplates} の認可単体テスト（CMP-260917-2102 Phase 1 の追撃）。
 *
 * <p>実機で ORGANIZATION スコープの MEMBER がテンプレート一覧（
 * {@code GET /api/v1/organizations/{orgId}/direct-mail-templates}）を取得できることを
 * 確認済み。ダイレクトメールは ORGANIZATION サイドバーでのみ ADMIN 限定表示している機能
 * （TEAM サイドバーには導線自体が無い）だが、{@code DirectMailScopeContractIT}
 * 「一般メンバーのテンプレート一覧は200（閲覧系はcheckMembership）」が
 * {@code /api/v1/teams/{teamId}/direct-mail-templates} 側の TEAM 契約を意図的に固定している
 * ため、これを壊さないよう {@link DirectMailService#listMails} と同型のスコープ分岐にする
 * （ORGANIZATION のみ ADMIN 必須、TEAM は checkMembership のまま維持）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectMailTemplateService 認可単体テスト（listTemplates・スコープ差分）")
class DirectMailTemplateServiceListTemplatesAuthzTest {

    @Mock private AccessControlService accessControlService;
    @Mock private DirectMailTemplateRepository templateRepository;
    @Mock private DirectMailMapper directMailMapper;

    @InjectMocks
    private DirectMailTemplateService service;

    private static final Long SCOPE_ID = 9L;
    private static final Long ACTOR_ID = 100L;

    @Nested
    @DisplayName("ORGANIZATIONスコープ")
    class OrganizationScope {

        @Test
        @DisplayName("MEMBERは403（COMMON_002）で拒否される")
        void member_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq("ORGANIZATION"));

            assertThatThrownBy(() -> service.listTemplates("ORGANIZATION", SCOPE_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("ADMINは200相当で取得できる")
        void admin_isAllowed() {
            doNothing().when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq("ORGANIZATION"));
            given(templateRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc("ORGANIZATION", SCOPE_ID))
                    .willReturn(java.util.List.of());
            given(directMailMapper.toTemplateResponseList(java.util.List.of())).willReturn(java.util.List.of());

            assertThatCode(() -> service.listTemplates("ORGANIZATION", SCOPE_ID, ACTOR_ID))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("TEAMスコープ（DirectMailScopeContractITの既存契約を維持）")
    class TeamScope {

        @Test
        @DisplayName("MEMBERは200相当で取得できる（スコープ契約維持）")
        void member_isAllowed() {
            doNothing().when(accessControlService).checkMembership(anyLong(), eq(SCOPE_ID), eq("TEAM"));
            given(templateRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc("TEAM", SCOPE_ID))
                    .willReturn(java.util.List.of());
            given(directMailMapper.toTemplateResponseList(java.util.List.of())).willReturn(java.util.List.of());

            assertThatCode(() -> service.listTemplates("TEAM", SCOPE_ID, ACTOR_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("非メンバーは403（COMMON_002）で拒否される")
        void nonMember_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(anyLong(), eq(SCOPE_ID), eq("TEAM"));

            assertThatThrownBy(() -> service.listTemplates("TEAM", SCOPE_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }
}
