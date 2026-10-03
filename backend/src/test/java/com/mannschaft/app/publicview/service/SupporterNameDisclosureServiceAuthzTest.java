package com.mannschaft.app.publicview.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.SupporterNameDisclosurePatchRequest;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.publicview.repository.OrganizationNameDisclosureChangeLogRepository;
import com.mannschaft.app.publicview.repository.TeamNameDisclosureChangeLogRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SupporterNameDisclosureService} の per-scope 認可（認可根治 Phase 3-a / 生穴封鎖）単体テスト。
 *
 * <p>本サービスはかつて Service 層に認可がなく「認証済みなら誰でも他団体の投稿者識別モードを切替・
 * 履歴閲覧できる」生穴であった。本テストは Service 層明示認可（SYSTEM_ADMIN 短絡 or
 * 当該スコープ ADMIN/DEPUTY_ADMIN）が効くことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupporterNameDisclosureService 認可（生穴封鎖）単体テスト")
class SupporterNameDisclosureServiceAuthzTest {

    @Mock
    private TeamRepository teamRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private TeamNameDisclosureChangeLogRepository teamChangeLogRepository;
    @Mock
    private OrganizationNameDisclosureChangeLogRepository orgChangeLogRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SupporterNameDisclosureService service;

    private static final Long SCOPE_ID = 100L;
    private static final Long OPERATOR_ID = 9L;

    private SupporterNameDisclosurePatchRequest patch() {
        return new SupporterNameDisclosurePatchRequest(NameDisclosureMode.REAL_NAME, true);
    }

    @Nested
    @DisplayName("チーム切替・履歴")
    class Team {

        @Test
        @DisplayName("非権限者の patch は COMMON_002（confirmed チェック・チーム取得より前に弾く）")
        void patch_非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> service.patchTeamDisclosure(SCOPE_ID, OPERATOR_ID, patch()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(teamRepository, never()).findById(any());
        }

        @Test
        @DisplayName("非権限者の history は COMMON_002")
        void history_非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> service.getTeamChangeHistory(SCOPE_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(teamChangeLogRepository, never()).findByTeamIdOrderByChangedAtDesc(any());
        }
    }

    @Nested
    @DisplayName("組織切替・履歴")
    class Organization {

        @Test
        @DisplayName("非権限者の patch は COMMON_002")
        void patch_非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.patchOrganizationDisclosure(SCOPE_ID, OPERATOR_ID, patch()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(organizationRepository, never()).findById(any());
        }

        @Test
        @DisplayName("非権限者の history は COMMON_002")
        void history_非権限者_COMMON_002() {
            given(accessControlService.isSystemAdmin(OPERATOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(OPERATOR_ID, SCOPE_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.getOrganizationChangeHistory(SCOPE_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

            verify(orgChangeLogRepository, never()).findByOrganizationIdOrderByChangedAtDesc(any());
        }
    }

    /**
     * 回帰防止: 切替時に既存エンティティを UPDATE すること（toBuilder id 欠落 INSERT 化の根治・PR #1643 と同型）。
     *
     * <p>かつて {@code team.toBuilder().build()} で作り直して save していたため、継承フィールド id が
     * 引き継がれず id=null の新インスタンスを INSERT し slug 一意制約違反で 500 になっていた。
     * 直接ミューテートに変えたことで save に渡るのは findById で取得した「まさにその」managed entity
     * （id 保持 → UPDATE）であることを検証する。</p>
     */
    @Nested
    @DisplayName("切替の id 保持（toBuilder INSERT 化の回帰防止）")
    class UpdatePreservesId {

        private static final Long TEAM_ID = 100L;
        private static final Long ORG_ID = 200L;
        private static final Long ADMIN_ID = 9L;

        @Test
        @DisplayName("チーム: 既存エンティティをUPDATE_id不変かつ新規行を作らない")
        void チーム切替_既存エンティティをUPDATE() {
            // Given: id 採番済み（DISPLAY_NAME → REAL_NAME へ変更）
            TeamEntity team = TeamEntity.builder()
                    .slug("test-team")
                    .name("テスト").template("sports")
                    .visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(false)
                    .supporterNameDisclosure(NameDisclosureMode.DISPLAY_NAME)
                    .build();
            ReflectionTestUtils.setField(team, "id", TEAM_ID);
            given(accessControlService.isSystemAdmin(ADMIN_ID)).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
            given(teamRepository.save(any(TeamEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            service.patchTeamDisclosure(TEAM_ID, ADMIN_ID,
                    new SupporterNameDisclosurePatchRequest(NameDisclosureMode.REAL_NAME, true));

            // Then
            ArgumentCaptor<TeamEntity> captor =
                    ArgumentCaptor.forClass(TeamEntity.class);
            verify(teamRepository).save(captor.capture());
            TeamEntity saved = captor.getValue();
            assertThat(saved).isSameAs(team);
            assertThat(saved.getId()).isEqualTo(TEAM_ID); // id 欠落（INSERT 化）が起きていない
            assertThat(saved.getSupporterNameDisclosure()).isEqualTo(NameDisclosureMode.REAL_NAME);
            assertThat(saved.getSlug()).isEqualTo("test-team"); // slug 据置
        }

        @Test
        @DisplayName("組織: 既存エンティティをUPDATE_id不変かつ新規行を作らない")
        void 組織切替_既存エンティティをUPDATE() {
            // Given: id 採番済み（DISPLAY_NAME → REAL_NAME へ変更）
            OrganizationEntity orgEntity = OrganizationEntity.builder()
                    .slug("test-org")
                    .name("テスト組織")
                    .orgType(OrganizationEntity.OrgType.SCHOOL)
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .supporterEnabled(false)
                    .supporterNameDisclosure(NameDisclosureMode.DISPLAY_NAME)
                    .build();
            ReflectionTestUtils.setField(orgEntity, "id", ORG_ID);
            given(accessControlService.isSystemAdmin(ADMIN_ID)).willReturn(true);
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(orgEntity));
            given(organizationRepository.save(any(OrganizationEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            service.patchOrganizationDisclosure(ORG_ID, ADMIN_ID,
                    new SupporterNameDisclosurePatchRequest(NameDisclosureMode.REAL_NAME, true));

            // Then
            ArgumentCaptor<OrganizationEntity> captor =
                    ArgumentCaptor.forClass(OrganizationEntity.class);
            verify(organizationRepository).save(captor.capture());
            OrganizationEntity saved = captor.getValue();
            assertThat(saved).isSameAs(orgEntity);
            assertThat(saved.getId()).isEqualTo(ORG_ID); // id 欠落（INSERT 化）が起きていない
            assertThat(saved.getSupporterNameDisclosure()).isEqualTo(NameDisclosureMode.REAL_NAME);
            assertThat(saved.getSlug()).isEqualTo("test-org"); // slug 据置
        }
    }
}
