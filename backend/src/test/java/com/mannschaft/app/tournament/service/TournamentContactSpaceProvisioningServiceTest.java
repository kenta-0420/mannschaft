package com.mannschaft.app.tournament.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.service.TournamentChatChannelService;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TournamentContactSpaceProvisioningService} の単体テスト（F08.7.1 連絡機能 §3）。
 *
 * <p>掲示板スペース（デフォルトカテゴリ生成）＋チャットスペースの払い出しと、
 * 二重作成されない冪等性を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentContactSpaceProvisioningService 単体テスト")
class TournamentContactSpaceProvisioningServiceTest {

    @Mock
    private TournamentContactSpaceRepository contactSpaceRepository;
    @Mock
    private BulletinCategoryRepository bulletinCategoryRepository;
    @Mock
    private TournamentChatChannelService tournamentChatChannelService;

    @InjectMocks
    private TournamentContactSpaceProvisioningService service;

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;

    /** id を持つ保存済みカテゴリの体裁を mock で再現する（BaseEntity に setId が無いため）。 */
    private static BulletinCategoryEntity savedCategoryMock(Long id) {
        BulletinCategoryEntity e = mock(BulletinCategoryEntity.class);
        // 代表カテゴリ（1件目）の id のみ ref に使われるため、2件目以降は未使用となりうる → lenient
        lenient().when(e.getId()).thenReturn(id);
        return e;
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("provisionForTournament")
    class ProvisionForTournament {

        @Test
        @DisplayName("掲示板（デフォルトカテゴリ2件）＋チャットの2スペースを払い出し contact_space に記録する")
        void provisionsBothSpaces() {
            // BULLETIN / CHAT とも未払い出し
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.empty());
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.CHAT))
                    .willReturn(Optional.empty());
            // 既存カテゴリなし
            given(bulletinCategoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(
                    ScopeType.TOURNAMENT, TOURNAMENT_ID))
                    .willReturn(List.of());
            BulletinCategoryEntity cat1 = savedCategoryMock(11L);
            BulletinCategoryEntity cat2 = savedCategoryMock(12L);
            given(bulletinCategoryRepository.save(any(BulletinCategoryEntity.class)))
                    .willReturn(cat1, cat2);
            ChatChannelEntity channel = mock(ChatChannelEntity.class);
            when(channel.getId()).thenReturn(77L);
            given(tournamentChatChannelService.createForTournament(eq(TOURNAMENT_ID), any()))
                    .willReturn(channel);

            service.provisionForTournament(TOURNAMENT_ID, "テスト大会");

            // デフォルトカテゴリ 2 件生成
            verify(bulletinCategoryRepository, times(2)).save(any(BulletinCategoryEntity.class));
            // チャットチャンネル払い出し
            verify(tournamentChatChannelService).createForTournament(eq(TOURNAMENT_ID), any());
            // contact_space に BULLETIN / CHAT の 2 行記録
            verify(contactSpaceRepository, times(2)).save(any(TournamentContactSpaceEntity.class));
        }

        @Test
        @DisplayName("冪等性: BULLETIN/CHAT とも既存なら二重作成しない")
        void idempotent() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.of(TournamentContactSpaceEntity.builder()
                            .scopeType(ContactSpaceScopeType.TOURNAMENT).scopeId(TOURNAMENT_ID)
                            .spaceKind(ContactSpaceKind.BULLETIN).refId(11L).build()));
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.CHAT))
                    .willReturn(Optional.of(TournamentContactSpaceEntity.builder()
                            .scopeType(ContactSpaceScopeType.TOURNAMENT).scopeId(TOURNAMENT_ID)
                            .spaceKind(ContactSpaceKind.CHAT).refId(77L).build()));

            service.provisionForTournament(TOURNAMENT_ID, "テスト大会");

            verify(bulletinCategoryRepository, never()).save(any());
            verify(tournamentChatChannelService, never()).createForTournament(any(), any());
            verify(contactSpaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("競合耐性(M1): 掲示板スペース save が UNIQUE 違反でも catch→再取得し巻き添え失敗しない")
        void bulletinSaveUniqueViolationIsRecovered() {
            // BULLETIN は未払い出しと判定 → save で UNIQUE 違反（並行払い出し）→ 再取得で吸収
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.empty())
                    .willReturn(Optional.of(TournamentContactSpaceEntity.builder()
                            .scopeType(ContactSpaceScopeType.TOURNAMENT).scopeId(TOURNAMENT_ID)
                            .spaceKind(ContactSpaceKind.BULLETIN).refId(11L).build()));
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.CHAT))
                    .willReturn(Optional.of(TournamentContactSpaceEntity.builder()
                            .scopeType(ContactSpaceScopeType.TOURNAMENT).scopeId(TOURNAMENT_ID)
                            .spaceKind(ContactSpaceKind.CHAT).refId(77L).build()));
            given(bulletinCategoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(
                    ScopeType.TOURNAMENT, TOURNAMENT_ID))
                    .willReturn(List.of());
            BulletinCategoryEntity cat1 = savedCategoryMock(11L);
            BulletinCategoryEntity cat2 = savedCategoryMock(12L);
            given(bulletinCategoryRepository.save(any(BulletinCategoryEntity.class)))
                    .willReturn(cat1, cat2);
            given(contactSpaceRepository.save(any(TournamentContactSpaceEntity.class)))
                    .willThrow(new org.springframework.dao.DataIntegrityViolationException("uk_contact_space"));

            // 例外を投げず正常終了する（巻き添え失敗しない）
            service.provisionForTournament(TOURNAMENT_ID, "テスト大会");

            // save 試行は 1 回 → 競合検知後は再取得（findBy が 2 回呼ばれる）で吸収
            verify(contactSpaceRepository, times(1)).save(any(TournamentContactSpaceEntity.class));
            verify(contactSpaceRepository, times(2)).findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN);
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("provisionForDivision")
    class ProvisionForDivision {

        @Test
        @DisplayName("ディビジョンの掲示板＋チャットを払い出す")
        void provisionsBothSpaces() {
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID, ContactSpaceKind.BULLETIN))
                    .willReturn(Optional.empty());
            given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                    ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID, ContactSpaceKind.CHAT))
                    .willReturn(Optional.empty());
            given(bulletinCategoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(
                    ScopeType.TOURNAMENT_DIVISION, DIVISION_ID))
                    .willReturn(List.of());
            BulletinCategoryEntity cat1 = savedCategoryMock(21L);
            BulletinCategoryEntity cat2 = savedCategoryMock(22L);
            given(bulletinCategoryRepository.save(any(BulletinCategoryEntity.class)))
                    .willReturn(cat1, cat2);
            ChatChannelEntity channel = mock(ChatChannelEntity.class);
            when(channel.getId()).thenReturn(88L);
            given(tournamentChatChannelService.createForDivision(eq(DIVISION_ID), any()))
                    .willReturn(channel);

            service.provisionForDivision(DIVISION_ID, "テスト大会 1部");

            verify(bulletinCategoryRepository, times(2)).save(any(BulletinCategoryEntity.class));
            verify(tournamentChatChannelService).createForDivision(eq(DIVISION_ID), any());
            verify(contactSpaceRepository, times(2)).save(any(TournamentContactSpaceEntity.class));
        }
    }
}
