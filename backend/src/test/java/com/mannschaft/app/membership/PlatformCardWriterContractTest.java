package com.mannschaft.app.membership;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import com.mannschaft.app.membership.service.PlatformCardWriter;
import com.mannschaft.app.membership.mapper.PlatformCardMapper;
import org.mapstruct.factory.Mappers;
import com.mannschaft.app.membership.service.QrTokenService;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** writer業務契約の補完UT。実TX/NULL一意制約/20並列は実MySQL ITが保証する。 */
class PlatformCardWriterContractTest {
    private MemberCardRepository cards;
    private PlatformCardWriter writer;
    @BeforeEach
    void 準備() {
        cards = mock(MemberCardRepository.class);
        writer = new PlatformCardWriter(cards, new QrTokenService(Clock.systemUTC()), Mappers.getMapper(PlatformCardMapper.class));
    }

    @Test
    void 初回だけactive共通会員証を保存する() {
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L, ScopeType.PLATFORM)).thenReturn(Optional.empty());
        when(cards.saveAndFlush(any())).thenAnswer(inv -> ((MemberCardEntity) inv.getArgument(0)).toBuilder().id(9L).build());
        assertThat(writer.ensure(7L, "契約表示名").getMemberCardId()).isEqualTo(9L);
        var saved = ArgumentCaptor.forClass(MemberCardEntity.class);
        verify(cards).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(7L);
        assertThat(saved.getValue().getScopeType()).isEqualTo(ScopeType.PLATFORM);
        assertThat(saved.getValue().getScopeId()).isNull();
        assertThat(saved.getValue().getStatus()).isEqualTo(CardStatus.ACTIVE);
        assertThat(saved.getValue().getDisplayName()).isEqualTo("契約表示名");
        assertThat(saved.getValue().getQrSecret()).hasSize(64);
        assertThat(saved.getValue().getCardCode()).hasSize(36);
        assertThat(saved.getValue().getCardNumber()).isNotBlank().hasSizeLessThanOrEqualTo(20);
    }

    @Test
    void active既存カードは同じIDを返して保存しない() {
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L, ScopeType.PLATFORM)).thenReturn(Optional.of(card(CardStatus.ACTIVE)));
        assertThat(writer.ensure(7L, "現在名").getMemberCardId()).isEqualTo(9L);
        verify(cards, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, names = {"SUSPENDED", "REVOKED"})
    void 停止無効カードを復活も作成もしない(CardStatus status) {
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L, ScopeType.PLATFORM)).thenReturn(Optional.of(card(status)));
        assertThatThrownBy(() -> writer.ensure(7L, "現在名")).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode().getCode()).isEqualTo("MEMBERSHIP_003"));
        verify(cards, never()).saveAndFlush(any());
    }

    @Test
    void 不正な非NULL共通scopeは再発行しない() {
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L, ScopeType.PLATFORM))
                .thenReturn(Optional.of(card(CardStatus.ACTIVE).toBuilder().scopeId(13L).build()));
        assertThatThrownBy(() -> writer.ensure(7L, "現在名")).isInstanceOf(BusinessException.class);
        verify(cards, never()).saveAndFlush(any());
    }

    @Test
    void 競合後の再読取は既存カードのみを返す() {
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L, ScopeType.PLATFORM)).thenReturn(Optional.of(card(CardStatus.ACTIVE)));
        assertThat(writer.findExisting(7L)).get().extracting("memberCardId").isEqualTo(9L);
        verify(cards, never()).saveAndFlush(any());
    }

    @Test
    void 競合後に既存がなければ空を返して作成しない() {
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L, ScopeType.PLATFORM)).thenReturn(Optional.empty());
        assertThat(writer.findExisting(7L)).isEmpty();
        verify(cards, never()).saveAndFlush(any());
    }

    @Test
    void 一意制約失敗を同transactionで握り潰さない() {
        when(cards.findByUserIdAndScopeTypeAndDeletedAtIsNull(7L, ScopeType.PLATFORM)).thenReturn(Optional.empty());
        var failure = new DataIntegrityViolationException("duplicate-contract");
        when(cards.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> writer.ensure(7L, "現在名")).isSameAs(failure);
    }

    private MemberCardEntity card(CardStatus status) {
        return MemberCardEntity.builder().id(9L).userId(7L).scopeType(ScopeType.PLATFORM).status(status).build();
    }
}
