package com.mannschaft.app.village.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageCharterDrafterRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageUserCleanerEventListener} 単体テスト（F17.1 Phase 1 B11）。
 *
 * <p>カバー観点（設計書 §7.1）:</p>
 * <ul>
 *   <li>nickname 物理削除（あれば）</li>
 *   <li>nickname 不在でも例外を投げない</li>
 *   <li>pin 全行物理削除（複数）</li>
 *   <li>membership 匿名化（leftAt セット + bannedReason=ANONYMIZED）</li>
 *   <li>個別ステップで例外が出てもロガー警告のみで握り潰す（メイン処理を止めない）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageUserCleanerEventListener 単体テスト")
class VillageUserCleanerEventListenerTest {

    private static final Long USER_ID = 9001L;
    private static final UUID NICKNAME_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000B1");
    private static final UUID PIN1_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000B2");
    private static final UUID PIN2_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000B3");
    private static final UUID MS1_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000B4");
    private static final UUID MS2_ID = UUID.fromString("01956c00-0000-7000-8000-0000000000B5");

    @Mock
    private UserVillageNicknameRepository nicknameRepository;
    @Mock
    private UserVillagePinRepository pinRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    /** F17.3 で退会時の憲章策定者 user_id NULL 化（anonymizeCharterDrafters）が追加されたため注入対象に追随。 */
    @Mock
    private VillageCharterDrafterRepository charterDrafterRepository;

    @InjectMocks
    private VillageUserCleanerEventListener listener;

    private UserVillageNicknameEntity nickname() {
        UserVillageNicknameEntity n = new UserVillageNicknameEntity();
        n.setId(NICKNAME_ID);
        return n;
    }

    private UserVillagePinEntity pin(UUID id) {
        UserVillagePinEntity p = new UserVillagePinEntity();
        p.setId(id);
        return p;
    }

    private VillageMembershipEntity membership(UUID id, UUID villageId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(USER_ID)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now().minusDays(10))
                .build();
        m.setId(id);
        return m;
    }

    @Test
    @DisplayName("ニックネームがある場合は物理削除する")
    void cleanupNicknames_present() {
        UserVillageNicknameEntity n = nickname();
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID)).willReturn(Optional.of(n));

        listener.cleanupNicknames(USER_ID);

        verify(nicknameRepository).delete(n);
    }

    @Test
    @DisplayName("ニックネームが無くても例外を投げない")
    void cleanupNicknames_absent() {
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID)).willReturn(Optional.empty());

        listener.cleanupNicknames(USER_ID);

        verify(nicknameRepository, never()).delete(any(UserVillageNicknameEntity.class));
    }

    @Test
    @DisplayName("ピン留めは全行物理削除する")
    void cleanupPins_multiple() {
        UserVillagePinEntity p1 = pin(PIN1_ID);
        UserVillagePinEntity p2 = pin(PIN2_ID);
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID)).willReturn(List.of(p1, p2));

        listener.cleanupPins(USER_ID);

        ArgumentCaptor<Iterable<UserVillagePinEntity>> cap =
                ArgumentCaptor.forClass(Iterable.class);
        verify(pinRepository).deleteAll(cap.capture());
        assertThat(cap.getValue()).containsExactly(p1, p2);
    }

    @Test
    @DisplayName("メンバーシップは leftAt + ANONYMIZED マーカーで更新する（投稿は保持）")
    void anonymizeMemberships_marksLeftWithAnonymizedMarker() {
        VillageMembershipEntity m1 = membership(MS1_ID, UUID.randomUUID());
        VillageMembershipEntity m2 = membership(MS2_ID, UUID.randomUUID());
        given(membershipRepository
                .findBySubjectTypeAndSubjectIdAndLeftAtIsNull(VillageSubjectType.USER, USER_ID))
                .willReturn(List.of(m1, m2));

        listener.anonymizeMemberships(USER_ID);

        ArgumentCaptor<Iterable<VillageMembershipEntity>> cap =
                ArgumentCaptor.forClass(Iterable.class);
        verify(membershipRepository).saveAll(cap.capture());
        List<VillageMembershipEntity> saved =
                java.util.stream.StreamSupport.stream(cap.getValue().spliterator(), false).toList();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getLeftAt()).isNotNull();
        assertThat(saved.get(0).getBannedReason()).isEqualTo(VillageUserCleanerEventListener.ANONYMIZED_MARKER);
        assertThat(saved.get(1).getLeftAt()).isNotNull();
        assertThat(saved.get(1).getBannedReason()).isEqualTo(VillageUserCleanerEventListener.ANONYMIZED_MARKER);
    }

    @Test
    @DisplayName("handleUserAnonymized: 内部で例外が出ても伝搬しない（ログ警告のみ）")
    void handleUserAnonymized_swallowsException() {
        given(nicknameRepository.findByUserIdAndVillageIdIsNull(USER_ID))
                .willThrow(new RuntimeException("simulated"));

        // 例外が伝播しないことを assertThatNoException で確認
        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(() -> listener.handleUserAnonymized(new UserAnonymizedEvent(USER_ID, "x@example.com")));
    }
}
