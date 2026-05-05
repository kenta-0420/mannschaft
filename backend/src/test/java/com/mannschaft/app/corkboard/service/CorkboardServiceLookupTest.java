package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardMapper;
import com.mannschaft.app.corkboard.dto.CorkboardDetailResponse;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardGroupRepository;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * F09.8 Phase A2: {@link CorkboardService#getBoardDetailByIdOnly} と
 * {@link CorkboardService#getOrganizationBoardDetail} の単体テスト。
 *
 * <p>scope-agnostic GET API および組織ボード GET API における
 * 権限チェック分岐（PERSONAL/TEAM/ORGANIZATION/未知 scope）と未存在ボード時の
 * エラーコードを網羅する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CorkboardService 詳細取得（Phase A2）単体テスト")
class CorkboardServiceLookupTest {

    @Mock private CorkboardRepository corkboardRepository;
    @Mock private CorkboardCardRepository cardRepository;
    @Mock private CorkboardGroupRepository groupRepository;
    @Mock private CorkboardMapper corkboardMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AccessControlService accessControlService;
    @Mock private CorkboardPermissionService corkboardPermissionService;

    @InjectMocks private CorkboardService service;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long BOARD_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long TEAM_ID = 300L;

    private CorkboardDetailResponse stubDetail;

    @BeforeEach
    void setUp() {
        stubDetail = new CorkboardDetailResponse(
                BOARD_ID, "PERSONAL", null, USER_ID, "詳細スタブ",
                "CORK", "ADMIN_ONLY", false, 0L,
                List.of(), List.of(), null, null, false);
    }

    /**
     * 単一テスト用にカード/グループ/Mapper のスタブを揃える（権限チェック通過時のみ呼ばれる）。
     * <p>{@link CorkboardEntity} の {@code id} は {@code @GeneratedValue} で永続化時に
     * 採番されるため、ビルダー組み立てだけでは {@code null} になる。よって
     * {@code findByCorkboardId...(null)} で呼び出される前提で {@code any()} を使う。</p>
     */
    private void givenDetailBuildStubs() {
        given(cardRepository.findByCorkboardIdAndIsArchivedFalseOrderByZIndexDesc(any()))
                .willReturn(List.of());
        given(groupRepository.findByCorkboardIdOrderByDisplayOrderAsc(any()))
                .willReturn(List.of());
        given(corkboardMapper.toDetailResponse(any(CorkboardEntity.class), anyList(), anyList(), anyBoolean()))
                .willReturn(stubDetail);
    }

    // ===========================================================
    //  getBoardDetailByIdOnly（scope-agnostic）
    // ===========================================================

    @Nested
    @DisplayName("getBoardDetailByIdOnly")
    class GetBoardDetailByIdOnly {

        @Test
        @DisplayName("PERSONAL: 所有者なら成功")
        void PERSONAL_所有者_成功() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("PERSONAL").ownerId(USER_ID).name("マイボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));
            givenDetailBuildStubs();

            CorkboardDetailResponse result = service.getBoardDetailByIdOnly(BOARD_ID, USER_ID);

            assertThat(result).isSameAs(stubDetail);
        }

        @Test
        @DisplayName("PERSONAL: 所有者以外は CORKBOARD_009")
        void PERSONAL_非所有者_例外() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("PERSONAL").ownerId(OTHER_USER_ID).name("他人ボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));

            assertThatThrownBy(() -> service.getBoardDetailByIdOnly(BOARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_009"));
        }

        @Test
        @DisplayName("PERSONAL: ownerId が null なら CORKBOARD_009")
        void PERSONAL_owner_null_例外() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("PERSONAL").ownerId(null).name("孤児ボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));

            assertThatThrownBy(() -> service.getBoardDetailByIdOnly(BOARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_009"));
        }

        @Test
        @DisplayName("TEAM: メンバーなら成功")
        void TEAM_メンバー_成功() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("TEAM").scopeId(TEAM_ID).name("チームボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            givenDetailBuildStubs();

            CorkboardDetailResponse result = service.getBoardDetailByIdOnly(BOARD_ID, USER_ID);

            assertThat(result).isSameAs(stubDetail);
        }

        @Test
        @DisplayName("TEAM: 非メンバーは CORKBOARD_009")
        void TEAM_非メンバー_例外() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("TEAM").scopeId(TEAM_ID).name("チームボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> service.getBoardDetailByIdOnly(BOARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_009"));
        }

        @Test
        @DisplayName("TEAM: scopeId が null なら CORKBOARD_009")
        void TEAM_scope_null_例外() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("TEAM").scopeId(null).name("孤児チームボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));

            assertThatThrownBy(() -> service.getBoardDetailByIdOnly(BOARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_009"));
        }

        @Test
        @DisplayName("ORGANIZATION: メンバーなら成功")
        void ORGANIZATION_メンバー_成功() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(ORG_ID).name("組織ボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            givenDetailBuildStubs();

            CorkboardDetailResponse result = service.getBoardDetailByIdOnly(BOARD_ID, USER_ID);

            assertThat(result).isSameAs(stubDetail);
        }

        @Test
        @DisplayName("ORGANIZATION: 非メンバーは CORKBOARD_009")
        void ORGANIZATION_非メンバー_例外() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(ORG_ID).name("組織ボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.getBoardDetailByIdOnly(BOARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_009"));
        }

        @Test
        @DisplayName("ボード未存在 → CORKBOARD_001")
        void ボード未存在_例外() {
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getBoardDetailByIdOnly(BOARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_001"));
        }

        @Test
        @DisplayName("未知の scope_type → CORKBOARD_009")
        void 未知スコープ_例外() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("UNKNOWN").name("謎ボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));

            assertThatThrownBy(() -> service.getBoardDetailByIdOnly(BOARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_009"));
        }
    }

    // ===========================================================
    //  getOrganizationBoardDetail
    // ===========================================================

    @Nested
    @DisplayName("getOrganizationBoardDetail")
    class GetOrganizationBoardDetail {

        @Test
        @DisplayName("正常系: 組織所属メンバーなら成功")
        void 所属メンバー_成功() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(ORG_ID).name("組織ボード").build();
            given(corkboardRepository.findByIdAndScopeTypeAndScopeId(BOARD_ID, "ORGANIZATION", ORG_ID))
                    .willReturn(Optional.of(board));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            givenDetailBuildStubs();

            CorkboardDetailResponse result = service.getOrganizationBoardDetail(ORG_ID, BOARD_ID, USER_ID);

            assertThat(result).isSameAs(stubDetail);
        }

        @Test
        @DisplayName("異常系: 非所属は CORKBOARD_009")
        void 非所属_例外() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(ORG_ID).name("組織ボード").build();
            given(corkboardRepository.findByIdAndScopeTypeAndScopeId(BOARD_ID, "ORGANIZATION", ORG_ID))
                    .willReturn(Optional.of(board));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.getOrganizationBoardDetail(ORG_ID, BOARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_009"));
        }

        @Test
        @DisplayName("異常系: ボード未存在 → CORKBOARD_001")
        void ボード未存在_例外() {
            given(corkboardRepository.findByIdAndScopeTypeAndScopeId(eq(BOARD_ID), eq("ORGANIZATION"), anyLong()))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getOrganizationBoardDetail(ORG_ID, BOARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CORKBOARD_001"));
        }
    }

    // ===========================================================
    //  F09.8 件A: viewerCanEdit がレスポンスへ反映されること
    // ===========================================================

    @Nested
    @DisplayName("viewerCanEdit がレスポンスへ反映される")
    class ViewerCanEditWiring {

        @Test
        @DisplayName("ADMIN ユーザーが ADMIN_ONLY ボードを取得すると viewerCanEdit=true となる")
        void ADMIN_ONLYボード_ADMIN_viewerCanEdit_true() {
            CorkboardEntity board = CorkboardEntity.builder()
                    .scopeType("TEAM").scopeId(TEAM_ID).editPolicy("ADMIN_ONLY")
                    .name("チームボード").build();
            given(corkboardRepository.findById(BOARD_ID)).willReturn(Optional.of(board));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(corkboardPermissionService.canEdit(board, USER_ID)).willReturn(true);

            CorkboardDetailResponse trueDetail = new CorkboardDetailResponse(
                    BOARD_ID, "TEAM", TEAM_ID, null, "チームボード",
                    "CORK", "ADMIN_ONLY", false, 0L,
                    List.of(), List.of(), null, null, true);

            given(cardRepository.findByCorkboardIdAndIsArchivedFalseOrderByZIndexDesc(any()))
                    .willReturn(List.of());
            given(groupRepository.findByCorkboardIdOrderByDisplayOrderAsc(any()))
                    .willReturn(List.of());
            given(corkboardMapper.toDetailResponse(any(CorkboardEntity.class), anyList(), anyList(), anyBoolean()))
                    .willReturn(trueDetail);

            CorkboardDetailResponse result = service.getBoardDetailByIdOnly(BOARD_ID, USER_ID);

            assertThat(result.getViewerCanEdit()).isTrue();
        }
    }
}
