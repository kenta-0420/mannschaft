package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentChatChannelService} の単体テスト（F08.7.1 連絡機能 §3）。
 *
 * <p>大会／ディビジョン専用チャットチャンネルの払い出し・冪等性・競合制御を検証する。
 * {@code source_type="TOURNAMENT"|"TOURNAMENT_DIVISION"} / {@code is_private=true} /
 * {@code team_id=org_id=null} を確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentChatChannelService 単体テスト")
class TournamentChatChannelServiceTest {

    @Mock
    private ChatChannelRepository chatChannelRepository;

    @InjectMocks
    private TournamentChatChannelService service;

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;

    @Nested
    @DisplayName("createForTournament")
    class CreateForTournament {

        @Test
        @DisplayName("正常系: channelType=TOURNAMENT_CHAT・source_type=TOURNAMENT・private・team/org=null で払い出す")
        void create_ok() {
            given(chatChannelRepository.findBySourceTypeAndSourceId("TOURNAMENT", TOURNAMENT_ID))
                    .willReturn(Optional.empty());
            given(chatChannelRepository.save(any(ChatChannelEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ChatChannelEntity result = service.createForTournament(TOURNAMENT_ID, "テスト大会 連絡");

            ArgumentCaptor<ChatChannelEntity> captor = ArgumentCaptor.forClass(ChatChannelEntity.class);
            verify(chatChannelRepository).save(captor.capture());
            ChatChannelEntity c = captor.getValue();
            assertThat(c.getChannelType()).isEqualTo(ChannelType.TOURNAMENT_CHAT);
            assertThat(c.getSourceType()).isEqualTo("TOURNAMENT");
            assertThat(c.getSourceId()).isEqualTo(TOURNAMENT_ID);
            assertThat(c.getTeamId()).isNull();
            assertThat(c.getOrganizationId()).isNull();
            assertThat(c.getIsPrivate()).isTrue();
            assertThat(c.getName()).isEqualTo("テスト大会 連絡");
            assertThat(result).isSameAs(c);
        }

        @Test
        @DisplayName("冪等性: 既存チャンネルがあれば save せず既存を返す")
        void idempotent() {
            ChatChannelEntity existing = ChatChannelEntity.builder()
                    .channelType(ChannelType.TOURNAMENT_CHAT)
                    .sourceType("TOURNAMENT")
                    .sourceId(TOURNAMENT_ID)
                    .build();
            given(chatChannelRepository.findBySourceTypeAndSourceId("TOURNAMENT", TOURNAMENT_ID))
                    .willReturn(Optional.of(existing));

            ChatChannelEntity result = service.createForTournament(TOURNAMENT_ID, "テスト大会 連絡");

            verify(chatChannelRepository, never()).save(any());
            assertThat(result).isSameAs(existing);
        }

        @Test
        @DisplayName("競合制御: save が UNIQUE 違反したら再取得して既存を返す")
        void conflict_refetch() {
            ChatChannelEntity existing = ChatChannelEntity.builder()
                    .channelType(ChannelType.TOURNAMENT_CHAT)
                    .sourceType("TOURNAMENT")
                    .sourceId(TOURNAMENT_ID)
                    .build();
            given(chatChannelRepository.findBySourceTypeAndSourceId("TOURNAMENT", TOURNAMENT_ID))
                    .willReturn(Optional.empty())   // 1回目: 既存なし
                    .willReturn(Optional.of(existing)); // 競合後の再取得
            given(chatChannelRepository.save(any(ChatChannelEntity.class)))
                    .willThrow(new DataIntegrityViolationException("dup"));

            ChatChannelEntity result = service.createForTournament(TOURNAMENT_ID, "テスト大会 連絡");

            assertThat(result).isSameAs(existing);
        }
    }

    @Nested
    @DisplayName("createForDivision")
    class CreateForDivision {

        @Test
        @DisplayName("正常系: channelType=TOURNAMENT_DIVISION_CHAT・source_type=TOURNAMENT_DIVISION で払い出す")
        void create_ok() {
            given(chatChannelRepository.findBySourceTypeAndSourceId("TOURNAMENT_DIVISION", DIVISION_ID))
                    .willReturn(Optional.empty());
            given(chatChannelRepository.save(any(ChatChannelEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ChatChannelEntity result = service.createForDivision(DIVISION_ID, "テスト大会 1部 連絡");

            ArgumentCaptor<ChatChannelEntity> captor = ArgumentCaptor.forClass(ChatChannelEntity.class);
            verify(chatChannelRepository).save(captor.capture());
            ChatChannelEntity c = captor.getValue();
            assertThat(c.getChannelType()).isEqualTo(ChannelType.TOURNAMENT_DIVISION_CHAT);
            assertThat(c.getSourceType()).isEqualTo("TOURNAMENT_DIVISION");
            assertThat(c.getSourceId()).isEqualTo(DIVISION_ID);
            assertThat(c.getIsPrivate()).isTrue();
            assertThat(result).isSameAs(c);
        }
    }
}
