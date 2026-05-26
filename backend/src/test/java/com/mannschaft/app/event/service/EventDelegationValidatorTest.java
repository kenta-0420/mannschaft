package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.repository.EventDelegationRepository;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.proxyvote.service.ProxyDelegationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link EventDelegationValidator} の単体テスト（§5.6 #9〜#11 連携検証中心）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventDelegationValidator 単体テスト")
class EventDelegationValidatorTest {

    @Mock private MembershipRepository membershipRepository;
    @Mock private EventDelegationRepository delegationRepository;
    @Mock private ProxyDelegationService proxyDelegationService;

    @InjectMocks
    private EventDelegationValidator validator;

    private static final Long SCOPE_ID = 1L;
    private static final Long DELEGATOR = 100L;
    private static final Long DELEGATE = 200L;

    private EventEntity baseEvent() {
        return EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .slug("ev")
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .status(EventStatus.PUBLISHED)
                .attendanceMode(EventAttendanceMode.RSVP)
                .build();
    }

    private void givenAllChecksPass() {
        given(membershipRepository.existsActiveByUserAndScope(any(), eq(ScopeType.TEAM), eq(SCOPE_ID))).willReturn(true);
        given(delegationRepository.findFirstByEventIdAndDelegatorIdAndStatusIn(any(), eq(DELEGATOR), any()))
                .willReturn(Optional.empty());
        given(delegationRepository.existsByDelegateIdAndStatusIn(eq(DELEGATE), any())).willReturn(false);
    }

    @Test
    @DisplayName("proxyVoteSessionId なし: #9-11 をスキップして成功")
    void 投票連携なし成功() {
        givenAllChecksPass();
        assertThatCode(() -> validator.validateForCreate(baseEvent(), DELEGATOR, DELEGATE, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("proxyVoteSessionId あり: セッションが事前条件を満たさないと 422")
    void 投票連携不適格() {
        givenAllChecksPass();
        given(proxyDelegationService.isSessionEligibleForEventDelegation(eq(99L), anyString(), anyLong(), eq(DELEGATE)))
                .willReturn(false);

        assertThatThrownBy(() -> validator.validateForCreate(baseEvent(), DELEGATOR, DELEGATE, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(EventErrorCode.DELEGATION_PROXY_VOTE_INVALID);
    }

    @Test
    @DisplayName("proxyVoteSessionId あり: セッションが適格なら成功")
    void 投票連携適格() {
        givenAllChecksPass();
        given(proxyDelegationService.isSessionEligibleForEventDelegation(eq(99L), anyString(), anyLong(), eq(DELEGATE)))
                .willReturn(true);

        assertThatCode(() -> validator.validateForCreate(baseEvent(), DELEGATOR, DELEGATE, 99L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("#1: allow_proxy_attendance=FALSE で 422")
    void 代理不許可() {
        EventEntity e = baseEvent().toBuilder().allowProxyAttendance(false).build();
        assertThatThrownBy(() -> validator.validateForCreate(e, DELEGATOR, DELEGATE, null))
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(EventErrorCode.DELEGATION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("#7: COMPLETED で 422")
    void 完了済み() {
        EventEntity e = baseEvent().toBuilder().status(EventStatus.COMPLETED).build();
        assertThatThrownBy(() -> validator.validateForCreate(e, DELEGATOR, DELEGATE, null))
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(EventErrorCode.DELEGATION_INVALID_EVENT_STATUS);
    }

    @Test
    @DisplayName("#6: 連鎖代理禁止で 422")
    void 連鎖代理() {
        given(membershipRepository.existsActiveByUserAndScope(any(), eq(ScopeType.TEAM), eq(SCOPE_ID))).willReturn(true);
        given(delegationRepository.findFirstByEventIdAndDelegatorIdAndStatusIn(any(), eq(DELEGATOR), any()))
                .willReturn(Optional.empty());
        given(delegationRepository.existsByDelegateIdAndStatusIn(eq(DELEGATE), any())).willReturn(true);

        assertThatThrownBy(() -> validator.validateForCreate(baseEvent(), DELEGATOR, DELEGATE, null))
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(EventErrorCode.DELEGATION_CHAINED);
    }
}
