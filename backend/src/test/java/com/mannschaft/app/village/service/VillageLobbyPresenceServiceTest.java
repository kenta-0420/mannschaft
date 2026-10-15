package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.dto.LobbyPresenceResponse;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageLobbyPresenceService} 単体テスト（F17.1 Phase 2）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>村メンバーが join すると Valkey にキーが設定されブロードキャストされる</li>
 *   <li>非メンバーが join しても何もしない</li>
 *   <li>ニックネームなしの場合は空文字でキーが設定される</li>
 *   <li>heartbeat でキーの TTL がリセットされる</li>
 *   <li>leave でキーが削除されブロードキャストされる</li>
 *   <li>getPresence でメンバーが在席リストを取得できる</li>
 *   <li>非メンバーが getPresence を呼ぶと BusinessException</li>
 *   <li>在席ゼロの場合は空リストを返す</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageLobbyPresenceService 単体テスト")
class VillageLobbyPresenceServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final Long USER_ID = 42L;

    @Mock
    StringRedisTemplate redisTemplate;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    SetOperations<String, String> setOps;
    @Mock
    UserVillageNicknameRepository nicknameRepository;
    @Mock
    VillageMembershipRepository membershipRepository;
    @Mock
    SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    VillageLobbyPresenceService service;

    @BeforeEach
    void setUp() {
        // 全テストで共有するスタブ。使わないテストもあるため lenient で登録する
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    // ========== join ==========

    @Test
    @DisplayName("join_villageメンバーの場合_Valkeyにキーが設定されブロードキャストされる")
    void join_villageMember_setsKeyAndBroadcasts() {
        // given
        VillageMembershipEntity membership = buildMembership(false);
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                .willReturn(Optional.of(membership));

        UserVillageNicknameEntity nicknameEntity = UserVillageNicknameEntity.builder()
                .userId(USER_ID)
                .nickname("てすと")
                .build();
        given(nicknameRepository.findByUserIdAndVillageId(USER_ID, VILLAGE_ID))
                .willReturn(Optional.of(nicknameEntity));

        // 在席なし（broadcast 用の keys スキャン）
        given(redisTemplate.keys(anyString())).willReturn(Set.of());

        // when
        service.join(VILLAGE_ID, USER_ID);

        // then
        verify(valueOps).set(
                contains("presence"),
                eq("てすと"),
                eq(VillageLobbyPresenceService.PRESENCE_TTL_SECONDS),
                eq(TimeUnit.SECONDS));
        verify(messagingTemplate).convertAndSend(
                contains("/topic/villages/" + VILLAGE_ID + "/lobby/presence"),
                any(LobbyPresenceResponse.class));
    }

    @Test
    @DisplayName("join_非メンバーの場合_何もしない")
    void join_nonMember_doesNothing() {
        // given
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                .willReturn(Optional.empty());

        // when
        service.join(VILLAGE_ID, USER_ID);

        // then
        verify(valueOps, never()).set(any(), any(), anyLong(), any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("join_ニックネームなしの場合_空文字でキーが設定される")
    void join_noNickname_setsEmptyString() {
        // given
        VillageMembershipEntity membership = buildMembership(false);
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                .willReturn(Optional.of(membership));

        given(nicknameRepository.findByUserIdAndVillageId(USER_ID, VILLAGE_ID))
                .willReturn(Optional.empty());
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willReturn(Optional.empty());

        given(redisTemplate.keys(anyString())).willReturn(Set.of());

        // when
        service.join(VILLAGE_ID, USER_ID);

        // then
        verify(valueOps).set(
                contains("presence"),
                eq(""),
                eq(VillageLobbyPresenceService.PRESENCE_TTL_SECONDS),
                eq(TimeUnit.SECONDS));
    }

    // ========== heartbeat ==========

    @Test
    @DisplayName("heartbeat_キーが存在する場合_TTLがリセットされる")
    void heartbeat_keyExists_resetsTtl() {
        // given
        given(redisTemplate.hasKey(anyString())).willReturn(Boolean.TRUE);

        // when
        service.heartbeat(VILLAGE_ID, USER_ID);

        // then
        verify(redisTemplate).expire(
                contains("presence"),
                eq(VillageLobbyPresenceService.PRESENCE_TTL_SECONDS),
                eq(TimeUnit.SECONDS));
    }

    // ========== leave ==========

    @Test
    @DisplayName("leave_キーが削除されbroadcastされる")
    void leave_deletesKeyAndBroadcasts() {
        // given
        given(redisTemplate.keys(anyString())).willReturn(Collections.emptySet());

        // when
        service.leave(VILLAGE_ID, USER_ID);

        // then
        verify(redisTemplate).delete(contains("presence"));
        verify(setOps).remove(contains("active-lobbies"), any());
        verify(messagingTemplate).convertAndSend(
                contains("/topic/villages/" + VILLAGE_ID + "/lobby/presence"),
                any(LobbyPresenceResponse.class));
    }

    // ========== getPresence ==========

    @Test
    @DisplayName("getPresence_メンバーの場合_在席リストを返す")
    void getPresence_member_returnsPresenceList() {
        // given
        VillageMembershipEntity membership = buildMembership(false);
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                .willReturn(Optional.of(membership));

        String presenceKey = "mannschaft:village:" + VILLAGE_ID + ":lobby:presence:" + USER_ID;
        given(redisTemplate.keys(anyString())).willReturn(Set.of(presenceKey));
        given(valueOps.get(presenceKey)).willReturn("テスト太郎");

        // when
        LobbyPresenceResponse res = service.getPresence(VILLAGE_ID, USER_ID);

        // then
        assertThat(res.activeCount()).isEqualTo(1);
        assertThat(res.members()).hasSize(1);
        assertThat(res.members().get(0).userId()).isEqualTo(USER_ID);
        assertThat(res.members().get(0).nickname()).isEqualTo("テスト太郎");
    }

    @Test
    @DisplayName("getPresence_非メンバーの場合_BusinessExceptionをスロー")
    void getPresence_nonMember_throwsBusinessException() {
        // given
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                .willReturn(Optional.empty());

        // then
        assertThrows(BusinessException.class, () -> service.getPresence(VILLAGE_ID, USER_ID));
    }

    @Test
    @DisplayName("getPresence_在席ゼロの場合_空リストを返す")
    void getPresence_noPresence_returnsEmptyList() {
        // given
        VillageMembershipEntity membership = buildMembership(false);
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, USER_ID))
                .willReturn(Optional.of(membership));

        given(redisTemplate.keys(anyString())).willReturn(Collections.emptySet());

        // when
        LobbyPresenceResponse res = service.getPresence(VILLAGE_ID, USER_ID);

        // then
        assertThat(res.members()).isEmpty();
        assertThat(res.activeCount()).isEqualTo(0);
    }

    // ========== ヘルパ ==========

    private VillageMembershipEntity buildMembership(boolean banned) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(USER_ID)
                .role(VillageRole.VILLAGER)
                .build();
        if (banned) {
            m.setBannedAt(java.time.LocalDateTime.now());
        }
        return m;
    }
}
