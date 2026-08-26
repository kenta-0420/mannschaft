package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link VillageAccessGate} の単体試練。
 *
 * <p>本ゲートの存在意義は「<b>非公開(UNLISTED)村の存在オラクルを塞ぐ</b>」ことにある。
 * よって本テストは HTTP ステータスではなく <b>{@link VillageErrorCode} そのものの一致</b>を見る。
 * 不在の村 ID と UNLISTED 村の非村人が同じ {@code VILLAGE_NOT_FOUND} を受け取れば、
 * 応答本文の {@code error.code} まで一致し、村の存在有無が推測できない。</p>
 *
 * <p>組合せ表: visibility(PUBLIC/UNLISTED) × deletedAt(null/非null) × archivedAt(null/非null)
 * × アクター種別(非会員/現役会員/BAN済/退村済/SYSTEM_ADMIN/null)。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VillageAccessGate — 非公開村の存在秘匿ゲート")
class VillageAccessGateTest {

    private static final UUID VILLAGE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID MISSING_VILLAGE_ID = UUID.fromString("018f0000-0000-7000-8000-0000000000ff");
    private static final Long STRANGER_ID = 1001L;
    private static final Long MEMBER_ID = 1002L;
    private static final Long BANNED_ID = 1003L;
    private static final Long LEFT_ID = 1004L;
    private static final Long ADMIN_ID = 1005L;

    @Mock
    private VillageRepository villageRepository;

    @Mock
    private VillageMembershipRepository membershipRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private VillageAccessGate gate;

    // ------------------------------------------------------------------
    // フィクスチャ
    // ------------------------------------------------------------------

    private VillageEntity village(VillageVisibility visibility, LocalDateTime deletedAt, LocalDateTime archivedAt) {
        VillageEntity v = VillageEntity.builder()
                .slug("test-village")
                .name("試験村")
                .visibility(visibility)
                .deletedAt(deletedAt)
                .archivedAt(archivedAt)
                .build();
        v.setId(VILLAGE_ID);
        return v;
    }

    /** 村を存在させ、既定では「誰も現役メンバーでない・誰も SYSTEM_ADMIN でない」状態にする。 */
    private void givenVillage(VillageEntity v) {
        lenient().when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(v));
        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(any(), any(), anyLong()))
                .thenReturn(Optional.empty());
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
    }

    /** BAN 済み・退村済みは {@code findActiveByVillageIdAndSubject} が空を返す（＝既定フィクスチャのまま）。 */
    private void givenActiveMember(Long userId) {
        lenient().when(membershipRepository
                        .findActiveByVillageIdAndSubject(eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(userId)))
                .thenReturn(Optional.of(new VillageMembershipEntity()));
    }

    private void givenSystemAdmin(Long userId) {
        lenient().when(accessControlService.isSystemAdmin(userId)).thenReturn(true);
    }

    private VillageErrorCode codeOf(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return (VillageErrorCode) ((BusinessException) t).getErrorCode();
    }

    // ==================================================================
    // loadActiveVillage
    // ==================================================================

    @Nested
    @DisplayName("loadActiveVillage — write/member-scoped 用")
    class LoadActiveVillage {

        @Test
        @DisplayName("不在の村 ID は VILLAGE_NOT_FOUND")
        void missingVillage() {
            when(villageRepository.findById(MISSING_VILLAGE_ID)).thenReturn(Optional.empty());

            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(MISSING_VILLAGE_ID, STRANGER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("villageId が null でも NPE にならず VILLAGE_NOT_FOUND")
        void nullVillageId() {
            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(null, STRANGER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("【核心】UNLISTED 村の非村人は、不在の村 ID とまったく同じ ErrorCode を受け取る")
        void unlistedStrangerIndistinguishableFromMissing() {
            givenVillage(village(VillageVisibility.UNLISTED, null, null));
            when(villageRepository.findById(MISSING_VILLAGE_ID)).thenReturn(Optional.empty());

            VillageErrorCode hidden = codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, STRANGER_ID)));
            VillageErrorCode missing =
                    codeOf(catchThrowable(() -> gate.loadActiveVillage(MISSING_VILLAGE_ID, STRANGER_ID)));

            assertThat(hidden).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(hidden).isEqualTo(missing);
        }

        @Test
        @DisplayName("PUBLIC 村の非村人はゲートを通過する（404 に倒れない）")
        void publicStrangerPasses() {
            VillageEntity v = village(VillageVisibility.PUBLIC, null, null);
            givenVillage(v);

            assertThatCode(() -> gate.loadActiveVillage(VILLAGE_ID, STRANGER_ID)).doesNotThrowAnyException();
            assertThat(gate.loadActiveVillage(VILLAGE_ID, STRANGER_ID)).isSameAs(v);
        }

        @Test
        @DisplayName("PUBLIC 村では追加クエリ0件（メンバーシップも SYSTEM_ADMIN も引かない）")
        void publicIssuesNoExtraQueries() {
            givenVillage(village(VillageVisibility.PUBLIC, null, null));

            gate.loadActiveVillage(VILLAGE_ID, STRANGER_ID);

            verify(membershipRepository, never()).findActiveByVillageIdAndSubject(any(), any(), anyLong());
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("UNLISTED 村の現役会員は通過し、SYSTEM_ADMIN 問い合わせで短絡する")
        void unlistedActiveMemberPassesAndShortCircuits() {
            VillageEntity v = village(VillageVisibility.UNLISTED, null, null);
            givenVillage(v);
            givenActiveMember(MEMBER_ID);

            assertThat(gate.loadActiveVillage(VILLAGE_ID, MEMBER_ID)).isSameAs(v);
            verify(accessControlService, never()).isSystemAdmin(anyLong());
        }

        @Test
        @DisplayName("UNLISTED 村の SYSTEM_ADMIN は通過する")
        void unlistedSystemAdminPasses() {
            VillageEntity v = village(VillageVisibility.UNLISTED, null, null);
            givenVillage(v);
            givenSystemAdmin(ADMIN_ID);

            assertThat(gate.loadActiveVillage(VILLAGE_ID, ADMIN_ID)).isSameAs(v);
        }

        @Test
        @DisplayName("BAN 済みの元村人は UNLISTED 村で VILLAGE_NOT_FOUND")
        void unlistedBannedHidden() {
            givenVillage(village(VillageVisibility.UNLISTED, null, null));

            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, BANNED_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("退村済みの元村人は UNLISTED 村で VILLAGE_NOT_FOUND")
        void unlistedLeftHidden() {
            givenVillage(village(VillageVisibility.UNLISTED, null, null));

            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, LEFT_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("actorUserId が null でも NPE にならず、UNLISTED 村は VILLAGE_NOT_FOUND")
        void unlistedAnonymousHidden() {
            givenVillage(village(VillageVisibility.UNLISTED, null, null));

            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, null))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("actorUserId が null でも PUBLIC 村は通過する")
        void publicAnonymousPasses() {
            VillageEntity v = village(VillageVisibility.PUBLIC, null, null);
            givenVillage(v);

            assertThat(gate.loadActiveVillage(VILLAGE_ID, null)).isSameAs(v);
        }

        @Test
        @DisplayName("削除済み村は PUBLIC でも VILLAGE_NOT_FOUND")
        void deletedPublicHidden() {
            givenVillage(village(VillageVisibility.PUBLIC, LocalDateTime.now(), null));

            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, MEMBER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("削除済み村は UNLISTED の現役会員に対しても VILLAGE_NOT_FOUND")
        void deletedUnlistedHiddenEvenForMember() {
            givenVillage(village(VillageVisibility.UNLISTED, LocalDateTime.now(), null));
            givenActiveMember(MEMBER_ID);

            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, MEMBER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("削除済みかつ凍結済みの村も VILLAGE_NOT_FOUND（削除判定が最優先）")
        void deletedAndArchivedHidden() {
            givenVillage(village(VillageVisibility.PUBLIC, LocalDateTime.now(), LocalDateTime.now()));

            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, MEMBER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("【順序の肝】凍結済み UNLISTED 村の非村人は VILLAGE_NOT_FOUND（409 の ALREADY_ARCHIVED ではない）")
        void archivedUnlistedStrangerGetsNotFoundNotArchived() {
            givenVillage(village(VillageVisibility.UNLISTED, null, LocalDateTime.now()));

            VillageErrorCode code = codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, STRANGER_ID)));

            assertThat(code).isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(code).isNotEqualTo(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }

        @Test
        @DisplayName("凍結済み PUBLIC 村は従来どおり VILLAGE_ALREADY_ARCHIVED（409）")
        void archivedPublicStillArchived() {
            givenVillage(village(VillageVisibility.PUBLIC, null, LocalDateTime.now()));

            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, STRANGER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }

        @Test
        @DisplayName("凍結済み UNLISTED 村の現役会員は VILLAGE_ALREADY_ARCHIVED（可視性を越えた先で凍結判定）")
        void archivedUnlistedMemberGetsArchived() {
            givenVillage(village(VillageVisibility.UNLISTED, null, LocalDateTime.now()));
            givenActiveMember(MEMBER_ID);

            assertThat(codeOf(catchThrowable(() -> gate.loadActiveVillage(VILLAGE_ID, MEMBER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
    }

    // ==================================================================
    // loadReadableVillage
    // ==================================================================

    @Nested
    @DisplayName("loadReadableVillage — read 公開用（凍結も404に畳む）")
    class LoadReadableVillage {

        @Test
        @DisplayName("PUBLIC 村の非村人は通過する")
        void publicStrangerPasses() {
            VillageEntity v = village(VillageVisibility.PUBLIC, null, null);
            givenVillage(v);

            assertThat(gate.loadReadableVillage(VILLAGE_ID, STRANGER_ID)).isSameAs(v);
        }

        @Test
        @DisplayName("凍結済み PUBLIC 村は VILLAGE_NOT_FOUND（read では 404 に畳む）")
        void archivedPublicFoldsTo404() {
            givenVillage(village(VillageVisibility.PUBLIC, null, LocalDateTime.now()));

            assertThat(codeOf(catchThrowable(() -> gate.loadReadableVillage(VILLAGE_ID, STRANGER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("凍結済み UNLISTED 村の現役会員も VILLAGE_NOT_FOUND")
        void archivedUnlistedMemberFoldsTo404() {
            givenVillage(village(VillageVisibility.UNLISTED, null, LocalDateTime.now()));
            givenActiveMember(MEMBER_ID);

            assertThat(codeOf(catchThrowable(() -> gate.loadReadableVillage(VILLAGE_ID, MEMBER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("UNLISTED 村の非村人・BAN済・退村済・匿名は VILLAGE_NOT_FOUND、現役会員は通過")
        void unlistedVisibility() {
            VillageEntity v = village(VillageVisibility.UNLISTED, null, null);
            givenVillage(v);

            for (Long actor : new Long[]{STRANGER_ID, BANNED_ID, LEFT_ID, null}) {
                assertThat(codeOf(catchThrowable(() -> gate.loadReadableVillage(VILLAGE_ID, actor))))
                        .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            }

            givenActiveMember(MEMBER_ID);
            assertThat(gate.loadReadableVillage(VILLAGE_ID, MEMBER_ID)).isSameAs(v);
        }

        @Test
        @DisplayName("UNLISTED 村の SYSTEM_ADMIN は通過する")
        void unlistedSystemAdminPasses() {
            VillageEntity v = village(VillageVisibility.UNLISTED, null, null);
            givenVillage(v);
            givenSystemAdmin(ADMIN_ID);

            assertThat(gate.loadReadableVillage(VILLAGE_ID, ADMIN_ID)).isSameAs(v);
        }

        @Test
        @DisplayName("削除済み・不在・null ID は VILLAGE_NOT_FOUND")
        void deletedAndNull() {
            givenVillage(village(VillageVisibility.PUBLIC, LocalDateTime.now(), null));
            when(villageRepository.findById(MISSING_VILLAGE_ID)).thenReturn(Optional.empty());

            assertThat(codeOf(catchThrowable(() -> gate.loadReadableVillage(VILLAGE_ID, MEMBER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(codeOf(catchThrowable(() -> gate.loadReadableVillage(MISSING_VILLAGE_ID, MEMBER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
            assertThat(codeOf(catchThrowable(() -> gate.loadReadableVillage(null, MEMBER_ID))))
                    .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
    }

    // ==================================================================
    // isVisibleTo
    // ==================================================================

    @Nested
    @DisplayName("isVisibleTo — 判定のみ")
    class IsVisibleTo {

        @Test
        @DisplayName("PUBLIC 村は誰にでも可視で、追加クエリを撃たない")
        void publicAlwaysVisible() {
            VillageEntity v = village(VillageVisibility.PUBLIC, null, null);

            assertThat(gate.isVisibleTo(v, STRANGER_ID)).isTrue();
            assertThat(gate.isVisibleTo(v, null)).isTrue();
            verify(membershipRepository, never()).findActiveByVillageIdAndSubject(any(), any(), anyLong());
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("UNLISTED 村は非村人・匿名・BAN済・退村済に不可視、現役会員と SYSTEM_ADMIN に可視")
        void unlistedVisibility() {
            VillageEntity v = village(VillageVisibility.UNLISTED, null, null);
            givenVillage(v);
            givenActiveMember(MEMBER_ID);
            givenSystemAdmin(ADMIN_ID);

            assertThat(gate.isVisibleTo(v, STRANGER_ID)).isFalse();
            assertThat(gate.isVisibleTo(v, null)).isFalse();
            assertThat(gate.isVisibleTo(v, BANNED_ID)).isFalse();
            assertThat(gate.isVisibleTo(v, LEFT_ID)).isFalse();
            assertThat(gate.isVisibleTo(v, MEMBER_ID)).isTrue();
            assertThat(gate.isVisibleTo(v, ADMIN_ID)).isTrue();
        }

        @Test
        @DisplayName("village が null なら不可視（NPE を投げない）")
        void nullVillageIsInvisible() {
            assertThat(gate.isVisibleTo(null, STRANGER_ID)).isFalse();
        }
    }

    // ==================================================================
    // filterVisible（一括版）
    // ==================================================================

    @Nested
    @DisplayName("filterVisible — 一括判定でも規則は単一版と同一であること")
    class FilterVisible {

        private static final UUID PUBLIC_ID = UUID.fromString("018f0000-0000-7000-8000-00000000000a");
        private static final UUID UNLISTED_MEMBER_ID = UUID.fromString("018f0000-0000-7000-8000-00000000000b");
        private static final UUID UNLISTED_OTHER_ID = UUID.fromString("018f0000-0000-7000-8000-00000000000c");

        private VillageEntity villageOf(UUID id, VillageVisibility visibility) {
            VillageEntity v = VillageEntity.builder()
                    .slug("bulk-" + id)
                    .name("一括判定村")
                    .visibility(visibility)
                    .build();
            v.setId(id);
            return v;
        }

        private VillageMembershipEntity membershipOf(UUID villageId, Long userId) {
            VillageMembershipEntity m = new VillageMembershipEntity();
            m.setVillageId(villageId);
            m.setSubjectType(VillageSubjectType.USER);
            m.setSubjectId(userId);
            return m;
        }

        @Test
        @DisplayName("PUBLIC は素通りし、UNLISTED は現役メンバーの村だけが残る")
        void keepsPublicAndOwnUnlistedOnly() {
            lenient().when(membershipRepository.findActiveUserMemberships(MEMBER_ID))
                    .thenReturn(java.util.List.of(membershipOf(UNLISTED_MEMBER_ID, MEMBER_ID)));
            lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);

            var result = gate.filterVisible(java.util.List.of(
                    villageOf(PUBLIC_ID, VillageVisibility.PUBLIC),
                    villageOf(UNLISTED_MEMBER_ID, VillageVisibility.UNLISTED),
                    villageOf(UNLISTED_OTHER_ID, VillageVisibility.UNLISTED)), MEMBER_ID);

            assertThat(result).extracting(VillageEntity::getId)
                    .as("非メンバーの UNLISTED 村が混じると、村名・村紋から存在が漏れる")
                    .containsExactly(PUBLIC_ID, UNLISTED_MEMBER_ID);
        }

        @Test
        @DisplayName("PUBLIC 村しか無ければ追加クエリを一切撃たない")
        void publicOnlyIssuesNoQuery() {
            var result = gate.filterVisible(
                    java.util.List.of(villageOf(PUBLIC_ID, VillageVisibility.PUBLIC)), STRANGER_ID);

            assertThat(result).hasSize(1);
            verifyNoInteractions(membershipRepository);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("村が何件あっても SYSTEM_ADMIN 判定は 1 回しか撃たない（N+1 の再発防止）")
        void systemAdminIsQueriedOnce() {
            lenient().when(membershipRepository.findActiveUserMemberships(anyLong()))
                    .thenReturn(java.util.List.of());
            lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);

            gate.filterVisible(java.util.List.of(
                    villageOf(UNLISTED_MEMBER_ID, VillageVisibility.UNLISTED),
                    villageOf(UNLISTED_OTHER_ID, VillageVisibility.UNLISTED),
                    villageOf(PUBLIC_ID, VillageVisibility.PUBLIC)), STRANGER_ID);

            verify(accessControlService).isSystemAdmin(STRANGER_ID);
            verify(membershipRepository).findActiveUserMemberships(STRANGER_ID);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN には UNLISTED も見える（単一版と同じ）")
        void systemAdminSeesUnlisted() {
            lenient().when(membershipRepository.findActiveUserMemberships(ADMIN_ID))
                    .thenReturn(java.util.List.of());
            givenSystemAdmin(ADMIN_ID);

            var result = gate.filterVisible(
                    java.util.List.of(villageOf(UNLISTED_OTHER_ID, VillageVisibility.UNLISTED)), ADMIN_ID);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("未ログイン(null)には PUBLIC しか見えず、問い合わせも撃たない")
        void anonymousSeesPublicOnly() {
            var result = gate.filterVisible(java.util.List.of(
                    villageOf(PUBLIC_ID, VillageVisibility.PUBLIC),
                    villageOf(UNLISTED_OTHER_ID, VillageVisibility.UNLISTED)), null);

            assertThat(result).extracting(VillageEntity::getId).containsExactly(PUBLIC_ID);
            verifyNoInteractions(membershipRepository);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("空・null 入力でも落ちない")
        void emptyInputIsSafe() {
            assertThat(gate.filterVisible(java.util.List.of(), MEMBER_ID)).isEmpty();
            assertThat(gate.filterVisible(null, MEMBER_ID)).isEmpty();
        }

        /**
         * 一括版だけが緩いと、同じ村が経路によって見えたり見えなかったりする穴になる。
         * 規則そのものが単一版と一致していることを、全組合せで機械的に突き合わせる。
         */
        @Test
        @DisplayName("等価性: 全組合せで isVisibleTo と同じ判定を返す")
        void agreesWithSingleVersion() {
            for (VillageVisibility visibility : VillageVisibility.values()) {
                for (boolean isMember : new boolean[]{true, false}) {
                    for (boolean isAdmin : new boolean[]{true, false}) {
                        VillageEntity v = villageOf(UNLISTED_OTHER_ID, visibility);

                        lenient().when(membershipRepository.findActiveUserMemberships(MEMBER_ID))
                                .thenReturn(isMember
                                        ? java.util.List.of(membershipOf(UNLISTED_OTHER_ID, MEMBER_ID))
                                        : java.util.List.of());
                        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(
                                        eq(UNLISTED_OTHER_ID), eq(VillageSubjectType.USER), eq(MEMBER_ID)))
                                .thenReturn(isMember
                                        ? Optional.of(membershipOf(UNLISTED_OTHER_ID, MEMBER_ID))
                                        : Optional.empty());
                        lenient().when(accessControlService.isSystemAdmin(MEMBER_ID)).thenReturn(isAdmin);

                        boolean single = gate.isVisibleTo(v, MEMBER_ID);
                        boolean bulk = !gate.filterVisible(java.util.List.of(v), MEMBER_ID).isEmpty();

                        assertThat(bulk)
                                .as("visibility=%s member=%s admin=%s で単一版と一括版の判定が食い違う",
                                        visibility, isMember, isAdmin)
                                .isEqualTo(single);
                    }
                }
            }
        }
    }
}
