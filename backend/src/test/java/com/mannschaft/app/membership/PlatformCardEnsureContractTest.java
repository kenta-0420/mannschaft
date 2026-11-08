package com.mannschaft.app.membership;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import com.mannschaft.app.membership.service.PlatformCardService;
import com.mannschaft.app.membership.service.PlatformCardWriter;
import com.mannschaft.app.membership.mapper.PlatformCardMapper;
import org.mapstruct.factory.Mappers;
import com.mannschaft.app.membership.service.QrTokenService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** ensureの本人状態・競合復帰を実段取り役と実Writerで補完。実TX/認可/20並列はITで保証する。 */
class PlatformCardEnsureContractTest {
    private UserService users;
    private MemberCardRepository cards;
    private PlatformCardService service;
    @BeforeEach
    void 準備() {
        users=mock(UserService.class);
        cards=mock(MemberCardRepository.class);
        service=new PlatformCardService(users,new PlatformCardWriter(cards,new QrTokenService(Clock.systemUTC()),Mappers.getMapper(PlatformCardMapper.class)));
    }
    @Test
    void 本人IDがなければ401相当でdomain照会しない() {
        reject(null,"COMMON_000");
        verifyNoInteractions(users,cards);
    }
    @Test
    void 本人が存在しなければカードを照会作成しない() {
        when(users.getActiveDisplayStates(List.of(7L))).thenReturn(Map.of());
        reject(7L,"COMMON_002");
        verifyNoInteractions(cards);
    }
    @Test
    void 本人がinactiveなら既存カードも照会しない() {
        when(users.getActiveDisplayStates(List.of(7L))).thenReturn(Map.of(7L,new UserService.ActiveDisplayState(false,null)));
        reject(7L,"COMMON_002");
        verifyNoInteractions(cards);
    }
    @Test
    void active本人だけが既存activeカードを再利用する() {
        active("表示名");
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM)).thenReturn(Optional.of(card(CardStatus.ACTIVE)));
        assertThat(service.ensure(7L).getData().getMemberCardId()).isEqualTo(9L);
        var order=inOrder(users,cards);
        order.verify(users).getActiveDisplayStates(List.of(7L));
        order.verify(cards).findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM);
        verify(cards,never()).saveAndFlush(any());
    }
    @ParameterizedTest
    @EnumSource(value=CardStatus.class,names={"SUSPENDED","REVOKED"})
    void active本人でも停止無効カードを復活させない(CardStatus status) {
        active("表示名");
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM)).thenReturn(Optional.of(card(status)));
        reject(7L,"MEMBERSHIP_003");
        verify(cards,never()).saveAndFlush(any());
    }
    @ParameterizedTest
    @ValueSource(strings={"uq_member_cards_active_platform_user_id","member_cards.uq_member_cards_active_platform_user_id"})
    void 共通slot一意競合の後に確定したカードを再読取する(String constraintName) {
        active("表示名");
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM))
                .thenReturn(Optional.empty(),Optional.of(card(CardStatus.ACTIVE)));
        when(cards.saveAndFlush(any())).thenThrow(slotFailure(constraintName));
        assertThat(service.ensure(7L).getData().getMemberCardId()).isEqualTo(9L);
        verify(cards,times(2)).findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM);
        verify(cards).saveAndFlush(any());
    }
    @Test
    void 再読取でも存在しなければ元DB失敗を返す() {
        active("表示名");
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM)).thenReturn(Optional.empty());
        var failure=slotFailure("uq_member_cards_active_platform_user_id");
        when(cards.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(()->service.ensure(7L)).isSameAs(failure);
        verify(cards,times(2)).findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM);
    }
    @Test
    void 表示名が空でもPII照会を増やさず会員証を確保する() {
        active(null);
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM)).thenReturn(Optional.empty());
        when(cards.saveAndFlush(any())).thenAnswer(inv->{
            MemberCardEntity saved=inv.getArgument(0);
            assertThat(saved.getDisplayName()).isNotBlank();
            return saved.toBuilder().id(9L).build();
        });
        assertThat(service.ensure(7L).getData().getMemberCardId()).isEqualTo(9L);
        verify(users).getActiveDisplayStates(List.of(7L));
        verifyNoMoreInteractions(users);
    }
    @Test
    void 別制約違反は同user勝者が存在しても回収しない() {
        active("表示名");
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM))
                .thenReturn(Optional.empty(),Optional.of(card(CardStatus.ACTIVE)));
        var failure=slotFailure("uq_member_cards_card_code");
        when(cards.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(()->service.ensure(7L)).isSameAs(failure);
        verify(cards).findByUserIdAndScopeTypeAndDeletedAtIsNull(7L,ScopeType.PLATFORM);
    }
    private DataIntegrityViolationException slotFailure(String constraintName) {
        return new DataIntegrityViolationException("synthetic-constraint",
                new ConstraintViolationException("synthetic-constraint",
                        new java.sql.SQLIntegrityConstraintViolationException("synthetic-duplicate","23000",1062),
                        constraintName));
    }
    private void active(String name) {
        when(users.getActiveDisplayStates(List.of(7L))).thenReturn(Map.of(7L,new UserService.ActiveDisplayState(true,name)));
    }
    private void reject(Long actor,String code) {
        assertThatThrownBy(()->service.ensure(actor)).isInstanceOfSatisfying(BusinessException.class,
                ex->assertThat(ex.getErrorCode().getCode()).isEqualTo(code));
    }
    private MemberCardEntity card(CardStatus status) {
        return MemberCardEntity.builder().id(9L).userId(7L).scopeType(ScopeType.PLATFORM).status(status).build();
    }
}
