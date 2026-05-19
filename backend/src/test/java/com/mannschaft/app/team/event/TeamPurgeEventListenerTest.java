package com.mannschaft.app.team.event;

import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPurgeEventListener 単体テスト")
class TeamPurgeEventListenerTest {

    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @InjectMocks
    private TeamPurgeEventListener listener;

    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("正常系: nullifyInvitedBy と nullifyRespondedBy が両方 userId で呼ばれる")
    void 正常_両方呼ばれる() {
        given(teamOrgMembershipRepository.nullifyInvitedBy(USER_ID)).willReturn(2);
        given(teamOrgMembershipRepository.nullifyRespondedBy(USER_ID)).willReturn(1);

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(teamOrgMembershipRepository).nullifyInvitedBy(USER_ID);
        verify(teamOrgMembershipRepository).nullifyRespondedBy(USER_ID);
    }

    @Test
    @DisplayName("異常系: nullifyInvitedBy が失敗しても nullifyRespondedBy は呼ばれる（例外は伝播しない）")
    void 異常_invitedBy失敗_respondedByは継続() {
        willThrow(new RuntimeException("DB error"))
                .given(teamOrgMembershipRepository).nullifyInvitedBy(USER_ID);
        given(teamOrgMembershipRepository.nullifyRespondedBy(USER_ID)).willReturn(1);

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(teamOrgMembershipRepository).nullifyInvitedBy(USER_ID);
        verify(teamOrgMembershipRepository).nullifyRespondedBy(USER_ID);
    }

    @Test
    @DisplayName("異常系: nullifyRespondedBy が失敗しても例外は伝播しない")
    void 異常_respondedBy失敗_例外伝播なし() {
        given(teamOrgMembershipRepository.nullifyInvitedBy(USER_ID)).willReturn(0);
        willThrow(new RuntimeException("DB error"))
                .given(teamOrgMembershipRepository).nullifyRespondedBy(USER_ID);

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(teamOrgMembershipRepository).nullifyInvitedBy(USER_ID);
        verify(teamOrgMembershipRepository).nullifyRespondedBy(USER_ID);
    }

    @Test
    @DisplayName("正常系: 対象 0 件でも例外なく完了する")
    void 正常_0件() {
        given(teamOrgMembershipRepository.nullifyInvitedBy(USER_ID)).willReturn(0);
        given(teamOrgMembershipRepository.nullifyRespondedBy(USER_ID)).willReturn(0);

        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(teamOrgMembershipRepository).nullifyInvitedBy(USER_ID);
        verify(teamOrgMembershipRepository).nullifyRespondedBy(USER_ID);
    }
}
