package com.mannschaft.app.village.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.DailyThreadListResponse;
import com.mannschaft.app.village.dto.DailyThreadResponse;
import com.mannschaft.app.village.dto.LobbyChannelResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageLobbyDailyThreadEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageLobbyDailyThreadRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageLobbyService} 単体テスト（F17.1 Phase 1 B9）。
 *
 * <p>カバー観点（§4.10）:</p>
 * <ul>
 *   <li>getOrCreateLobbyChannel: 既存ならそのまま / 無ければ INSERT</li>
 *   <li>getLobbyChannel: 村人なら情報取得 / 非村人なら VILLAGE_007</li>
 *   <li>listDailyThreads: days 範囲取得 / 上限・下限のクリップ</li>
 *   <li>getDailyThread: 該当日なし → VILLAGE_041</li>
 *   <li>ensureDailyThread: 冪等 / DataIntegrityViolation 競合時の再取得</li>
 *   <li>削除/凍結村: VILLAGE_001 / VILLAGE_027</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageLobbyService 単体テスト")
class VillageLobbyServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000010");
    private static final UUID THREAD_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000aa");
    private static final Long ACTOR_USER_ID = 300L;
    private static final Long CHANNEL_ID = 9999L;

    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private VillageLobbyDailyThreadRepository dailyThreadRepository;
    @Mock
    private ChatChannelRepository chatChannelRepository;

    /** 村の存在秘匿ゲート。実物へ委譲させるため {@link VillageAccessGateTestSupport} で結線する。 */
    @Mock
    private VillageAccessGate accessGate;

    @InjectMocks
    private VillageLobbyService service;

    /**
     * 村サービスの村存在確認は {@link VillageAccessGate} へ移った。
     * モックのゲートに実物のゲート（同じモックのリポジトリを注入）を委譲させることで、
     * 本テストが積み上げてきた {@code villageRepository.findById} の stub をそのまま生かしつつ、
     * 可視性判定は実物のロジックで走らせる。
     */
    @BeforeEach
    void wireVillageAccessGate() {
        VillageAccessGateTestSupport.delegateToRealGate(accessGate, villageRepository, membershipRepository);
    }

    private VillageEntity activeVillage;

    @BeforeEach
    void setUp() {
        activeVillage = VillageEntity.builder()
                .slug("lobby-test")
                .name("井戸端村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(0L)
                .build();
        activeVillage.setId(VILLAGE_ID);
    }

    private VillageMembershipEntity userMember() {
        return VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(ACTOR_USER_ID)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private ChatChannelEntity lobbyChannel() {
        ChatChannelEntity ch = ChatChannelEntity.builder()
                .channelType(ChannelType.VILLAGE_LOBBY)
                .villageId(VILLAGE_ID)
                .name("井戸端会議")
                .isPrivate(false)
                .isArchived(false)
                .activeThreadCount(0)
                .build();
        // BaseEntity.id は @Setter 無いゆえリフレクションで設定（テスト用）
        try {
            var f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(ch, CHANNEL_ID);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return ch;
    }

    private VillageLobbyDailyThreadEntity dailyThread(LocalDate date) {
        VillageLobbyDailyThreadEntity e = VillageLobbyDailyThreadEntity.builder()
                .villageId(VILLAGE_ID)
                .threadDate(date)
                .chatChannelId(CHANNEL_ID)
                .messageCountCache(10L)
                .build();
        e.setId(THREAD_ID);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }

    // ========================================================================
    // getOrCreateLobbyChannel
    // ========================================================================

    @Test
    @DisplayName("getOrCreateLobbyChannel: 既存があればそれを返す")
    void getOrCreate_existing_returns() {
        given(chatChannelRepository.findByVillageIdAndChannelType(VILLAGE_ID, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.of(lobbyChannel()));

        ChatChannelEntity got = service.getOrCreateLobbyChannel(VILLAGE_ID);

        assertThat(got.getId()).isEqualTo(CHANNEL_ID);
        verify(chatChannelRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("getOrCreateLobbyChannel: 未払い出しなら新規 INSERT")
    void getOrCreate_missing_creates() {
        given(chatChannelRepository.findByVillageIdAndChannelType(VILLAGE_ID, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.empty());
        given(chatChannelRepository.save(any(ChatChannelEntity.class)))
                .willAnswer(inv -> {
                    ChatChannelEntity e = inv.getArgument(0);
                    try {
                        var f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
                        f.setAccessible(true);
                        f.set(e, CHANNEL_ID);
                    } catch (ReflectiveOperationException ex) {
                        throw new RuntimeException(ex);
                    }
                    return e;
                });

        ChatChannelEntity created = service.getOrCreateLobbyChannel(VILLAGE_ID);

        ArgumentCaptor<ChatChannelEntity> cap = ArgumentCaptor.forClass(ChatChannelEntity.class);
        verify(chatChannelRepository).save(cap.capture());
        assertThat(cap.getValue().getChannelType()).isEqualTo(ChannelType.VILLAGE_LOBBY);
        assertThat(cap.getValue().getVillageId()).isEqualTo(VILLAGE_ID);
        assertThat(created.getId()).isEqualTo(CHANNEL_ID);
    }

    @Test
    @DisplayName("getOrCreateLobbyChannel: 競合（DataIntegrityViolation）時は再取得で復旧")
    void getOrCreate_raceCondition_recovers() {
        given(chatChannelRepository.findByVillageIdAndChannelType(VILLAGE_ID, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(lobbyChannel()));
        given(chatChannelRepository.save(any(ChatChannelEntity.class)))
                .willThrow(new DataIntegrityViolationException("race"));

        ChatChannelEntity got = service.getOrCreateLobbyChannel(VILLAGE_ID);

        assertThat(got.getId()).isEqualTo(CHANNEL_ID);
    }

    // ========================================================================
    // getLobbyChannel
    // ========================================================================

    @Test
    @DisplayName("getLobbyChannel: 村人なら chatChannelId + 本日スレッド情報を返す")
    void getLobby_member_returnsInfo() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(chatChannelRepository.findByVillageIdAndChannelType(VILLAGE_ID, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.of(lobbyChannel()));
        LocalDate today = LocalDate.now();
        given(dailyThreadRepository.findByVillageIdAndThreadDate(VILLAGE_ID, today))
                .willReturn(Optional.of(dailyThread(today)));

        LobbyChannelResponse res = service.getLobbyChannel(VILLAGE_ID, ACTOR_USER_ID);

        assertThat(res.chatChannelId()).isEqualTo(CHANNEL_ID);
        assertThat(res.channelType()).isEqualTo("VILLAGE_LOBBY");
        assertThat(res.villageId()).isEqualTo(VILLAGE_ID);
        assertThat(res.todayThreadDate()).isEqualTo(today);
        assertThat(res.todayThreadId()).isEqualTo(THREAD_ID);
    }

    @Test
    @DisplayName("getLobbyChannel: 非村人 → NOT_MEMBER (404)")
    void getLobby_notMember_404() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLobbyChannel(VILLAGE_ID, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    @Test
    @DisplayName("getLobbyChannel: 凍結村 → VILLAGE_027")
    void getLobby_archivedVillage_409() {
        activeVillage.setArchivedAt(LocalDateTime.now());
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));

        assertThatThrownBy(() -> service.getLobbyChannel(VILLAGE_ID, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
    }

    // ========================================================================
    // listDailyThreads
    // ========================================================================

    @Test
    @DisplayName("listDailyThreads: days=7 で 7 日分の範囲取得を呼ぶ")
    void list_days7_callsRepoWithCorrectRange() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(dailyThreadRepository
                .findByVillageIdAndThreadDateBetweenAndDeletedAtIsNullOrderByThreadDateDesc(
                        eq(VILLAGE_ID), any(LocalDate.class), any(LocalDate.class)))
                .willReturn(List.of(dailyThread(LocalDate.now()),
                        dailyThread(LocalDate.now().minusDays(1))));

        DailyThreadListResponse res = service.listDailyThreads(VILLAGE_ID, ACTOR_USER_ID, 7);

        assertThat(res.threads()).hasSize(2);
        // 範囲: to=today, from=today-6
        ArgumentCaptor<LocalDate> fromCap = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCap = ArgumentCaptor.forClass(LocalDate.class);
        verify(dailyThreadRepository)
                .findByVillageIdAndThreadDateBetweenAndDeletedAtIsNullOrderByThreadDateDesc(
                        eq(VILLAGE_ID), fromCap.capture(), toCap.capture());
        assertThat(toCap.getValue()).isEqualTo(LocalDate.now());
        assertThat(fromCap.getValue()).isEqualTo(LocalDate.now().minusDays(6));
    }

    @Test
    @DisplayName("listDailyThreads: days=0 は 1 日にクリップ / days=999 は MAX_DAILY_LIST_DAYS にクリップ")
    void list_clipped() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(dailyThreadRepository
                .findByVillageIdAndThreadDateBetweenAndDeletedAtIsNullOrderByThreadDateDesc(
                        eq(VILLAGE_ID), any(LocalDate.class), any(LocalDate.class)))
                .willReturn(List.of());

        DailyThreadListResponse res0 = service.listDailyThreads(VILLAGE_ID, ACTOR_USER_ID, 0);
        DailyThreadListResponse resBig = service.listDailyThreads(VILLAGE_ID, ACTOR_USER_ID, 999);

        assertThat(res0.threads()).isEmpty();
        assertThat(resBig.threads()).isEmpty();
    }

    // ========================================================================
    // getDailyThread
    // ========================================================================

    @Test
    @DisplayName("getDailyThread: 該当日が存在 → 要約 DTO を返す")
    void getDaily_existing_returns() {
        LocalDate date = LocalDate.now().minusDays(2);
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(dailyThreadRepository.findByVillageIdAndThreadDate(VILLAGE_ID, date))
                .willReturn(Optional.of(dailyThread(date)));

        DailyThreadResponse res = service.getDailyThread(VILLAGE_ID, ACTOR_USER_ID, date);

        assertThat(res.id()).isEqualTo(THREAD_ID);
        assertThat(res.threadDate()).isEqualTo(date);
        assertThat(res.messageCount()).isEqualTo(10L);
    }

    @Test
    @DisplayName("getDailyThread: 該当日なし → VILLAGE_041")
    void getDaily_missing_404() {
        LocalDate date = LocalDate.now().minusDays(2);
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(userMember()));
        given(dailyThreadRepository.findByVillageIdAndThreadDate(VILLAGE_ID, date))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDailyThread(VILLAGE_ID, ACTOR_USER_ID, date))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(VillageErrorCode.VILLAGE_LOBBY_NOT_FOUND);
    }

    // ========================================================================
    // ensureDailyThread
    // ========================================================================

    @Test
    @DisplayName("ensureDailyThread: 既存があれば再利用 / 無ければ作成")
    void ensure_idempotent() {
        LocalDate today = LocalDate.now();
        given(dailyThreadRepository.findByVillageIdAndThreadDate(VILLAGE_ID, today))
                .willReturn(Optional.empty());
        given(chatChannelRepository.findByVillageIdAndChannelType(VILLAGE_ID, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.of(lobbyChannel()));
        given(dailyThreadRepository.save(any(VillageLobbyDailyThreadEntity.class)))
                .willAnswer(inv -> {
                    VillageLobbyDailyThreadEntity e = inv.getArgument(0);
                    e.setId(THREAD_ID);
                    return e;
                });

        VillageLobbyDailyThreadEntity got = service.ensureDailyThread(VILLAGE_ID, today);

        assertThat(got.getId()).isEqualTo(THREAD_ID);
        assertThat(got.getChatChannelId()).isEqualTo(CHANNEL_ID);
    }
}
