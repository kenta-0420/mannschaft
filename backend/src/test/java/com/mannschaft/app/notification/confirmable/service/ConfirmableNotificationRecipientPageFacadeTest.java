package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientPageResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ConfirmableNotificationRecipientPageFacade} の単体テスト（CMP-260923-0954 W3b）。
 *
 * <p>認可を tx 本体（QueryService）から Facade へ移したため、認可の検証はここが持つ。
 * 応答の status・code・message まで含めた越境の一致は {@code ConfirmableNotificationExistenceOracleContractIT} が持つ。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmableNotificationRecipientPageFacade 単体テスト")
class ConfirmableNotificationRecipientPageFacadeTest {

    private static final Long TEAM_ID = 10L;
    private static final Long NOTIFICATION_ID = 100L;
    private static final Long USER_ID = 1L;

    @Mock
    private ConfirmableNotificationQueryService queryService;
    @Mock
    private ConfirmableScopeAuthorizer scopeAuthorizer;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ConfirmableNotificationRecipientPageFacade facade;

    private ConfirmableNotificationEntity notification(ScopeType scopeType, Long scopeId) {
        return ConfirmableNotificationEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title("テスト確認通知")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .totalRecipientCount(1)
                .build();
    }

    @Test
    @DisplayName("所属を確認し、ADMIN 判定を tx 本体へ渡してから呼ぶ（認可は tx 本体の前）")
    void 認可の後にtx本体を呼ぶ() {
        given(queryService.getDetail(NOTIFICATION_ID)).willReturn(notification(ScopeType.TEAM, TEAM_ID));
        given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        ConfirmableNotificationRecipientPageResponse expected =
                ConfirmableNotificationRecipientPageResponse.builder().build();
        given(queryService.getRecipientsPage(NOTIFICATION_ID, USER_ID, true, 0, 50, false)).willReturn(expected);

        ConfirmableNotificationRecipientPageResponse actual =
                facade.getRecipientsPage(ScopeType.TEAM, TEAM_ID, NOTIFICATION_ID, USER_ID, 0, 50, false);

        assertThat(actual).isSameAs(expected);
        verify(scopeAuthorizer).requireMember(
                USER_ID, ScopeType.TEAM, TEAM_ID, ConfirmableNotificationErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("通知が他スコープなら NOT_FOUND（不在IDと同じコード）。認可も tx 本体も呼ばない")
    void 他スコープの通知はNOT_FOUND() {
        given(queryService.getDetail(NOTIFICATION_ID)).willReturn(notification(ScopeType.TEAM, TEAM_ID + 1));

        assertThatThrownBy(() -> facade.getRecipientsPage(
                ScopeType.TEAM, TEAM_ID, NOTIFICATION_ID, USER_ID, 0, 50, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.NOT_FOUND);

        verifyNoInteractions(scopeAuthorizer);
        verify(queryService, never()).getRecipientsPage(any(), any(), anyBoolean(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("スコープの種別が違う通知（組織の通知をチームのパスで）も NOT_FOUND")
    void スコープ種別違いの通知はNOT_FOUND() {
        given(queryService.getDetail(NOTIFICATION_ID)).willReturn(notification(ScopeType.ORGANIZATION, TEAM_ID));

        assertThatThrownBy(() -> facade.getRecipientsPage(
                ScopeType.TEAM, TEAM_ID, NOTIFICATION_ID, USER_ID, 0, 50, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.NOT_FOUND);
        verifyNoInteractions(scopeAuthorizer);
    }

    @Test
    @DisplayName("通知が不在なら NOT_FOUND をそのまま返し、認可も tx 本体も呼ばない")
    void 不在の通知はNOT_FOUND() {
        given(queryService.getDetail(NOTIFICATION_ID))
                .willThrow(new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> facade.getRecipientsPage(
                ScopeType.TEAM, TEAM_ID, NOTIFICATION_ID, USER_ID, 0, 50, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.NOT_FOUND);
        verifyNoInteractions(scopeAuthorizer);
    }

    @Test
    @DisplayName("認可が拒否したら tx 本体は呼ばない（拒否の作り分けは Authorizer が決める）")
    void 認可が拒否したらtx本体を呼ばない() {
        given(queryService.getDetail(NOTIFICATION_ID)).willReturn(notification(ScopeType.TEAM, TEAM_ID));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(scopeAuthorizer).requireMember(
                        USER_ID, ScopeType.TEAM, TEAM_ID, ConfirmableNotificationErrorCode.NOT_FOUND);

        assertThatThrownBy(() -> facade.getRecipientsPage(
                ScopeType.TEAM, TEAM_ID, NOTIFICATION_ID, USER_ID, 0, 50, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(queryService, never()).getRecipientsPage(any(), any(), anyBoolean(), anyInt(), anyInt(), anyBoolean());
        verify(accessControlService, never()).isAdminOrAbove(any(), any(), any());
    }
}
