package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageInvitationAcceptResponse;
import com.mannschaft.app.village.dto.VillageInvitationCreateRequest;
import com.mannschaft.app.village.dto.VillageInvitationIssueResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageInvitationEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageInvitationRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import com.mannschaft.app.common.token.SecretTokenVault;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageInvitationService} の試練（テスト先行）。
 *
 * <h2>この機能が守るべき最重要契約</h2>
 * <p>非公開(UNLISTED)村は「存在そのものを秘匿する」契約を持つ（{@link VillageAccessGate} 参照）。
 * 招待はその秘匿を破らずに入村導線を再建するための仕組みである。したがって
 * <b>受諾が失敗したときの応答は、実在しないトークンへの応答と一字一句同じ</b>でなければならない。
 * 「期限切れです」「もう使われています」と理由を返した瞬間、それは
 * 「そのトークンは実在する ＝ その村は実在する」という答えになり、秘匿は消える。</p>
 *
 * <h2>AC-7 の書き方について</h2>
 * <p>「両者が一致すること」だけを見ると、両方が同じ内部エラーでも緑になってしまう。
 * 本テストは常に <b>(1) 不在応答と一致していること</b> と
 * <b>(2) その値が期待した具体値（{@link VillageErrorCode#VILLAGE_NOT_FOUND} ＝ VILLAGE_001）であること</b>
 * の両方を表明する。</p>
 *
 * <p>HTTP ステータス（404）と応答本文への写像は
 * {@code com.mannschaft.app.village.controller.VillageInvitationContractTest} が固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("村招待サービス — 非公開村の秘匿を破らない入村導線")
class VillageInvitationServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID OTHER_VILLAGE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000002");
    private static final UUID INVITATION_ID = UUID.fromString("018f1000-0000-7000-8000-0000000000a1");
    private static final UUID OTHER_INVITATION_ID = UUID.fromString("018f1000-0000-7000-8000-0000000000a2");
    private static final UUID HEADMAN_MEMBERSHIP_ID = UUID.fromString("018f1000-0000-7000-8000-0000000000b1");

    private static final Long HEADMAN_ID = 2001L;
    private static final Long VILLAGER_ID = 2003L;
    private static final Long STRANGER_ID = 2004L;
    private static final Long INVITEE_ID = 2005L;
    private static final Long BANNED_ID = 2006L;
    private static final Long OTHER_INVITEE_ID = 2007L;

    private static final String ABSENT_TOKEN = "absent-token-0000000000000000000000000000";
    private static final String VALID_TOKEN = "valid-token-00000000000000000000000000000";

    @Mock
    private VillageInvitationRepository invitationRepository;

    @Mock
    private VillageMembershipRepository membershipRepository;

    @Mock
    private VillageRepository villageRepository;

    @Mock
    private VillageAccessGate villageAccessGate;

    /**
     * 金庫は状態を持たない実物を注入する（モックにするとトークンが null になり
     * AC-3・AC-18 が検証できない）。アサーションには一切手を入れていない。
     */
    @Spy
    private SecretTokenVault secretTokenVault = new SecretTokenVault();

    /**
     * 期限判定の基準時刻を固定する。実時計だと「期限ちょうどの瞬間」を境界として
     * 検証できず、実行速度によって結果が変わる（負荷時のみ落ちる flaky の原因だった）。
     */
    @Spy
    private java.time.Clock clock = java.time.Clock.fixed(java.time.Instant.now(), java.time.ZoneOffset.UTC);

    @InjectMocks
    private VillageInvitationService service;

    @BeforeEach
    void setUpGate() {
        // ゲートはモックで素通りさせず、実物のロジックを走らせる（秘匿判定を殺さないため）。
        VillageAccessGateTestSupport.delegateToRealGate(
                villageAccessGate, villageRepository, membershipRepository);
        // 既定では、どのトークンも実在しない。
        lenient().when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        lenient().when(invitationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------
    // フィクスチャ
    // ------------------------------------------------------------------

    private VillageEntity village(UUID id, VillageVisibility visibility,
                                  LocalDateTime deletedAt, LocalDateTime archivedAt) {
        VillageEntity v = VillageEntity.builder()
                .slug("invite-village")
                .name("招待村")
                .visibility(visibility)
                .deletedAt(deletedAt)
                .archivedAt(archivedAt)
                .build();
        v.setId(id);
        return v;
    }

    private void villageExists(VillageEntity v) {
        lenient().when(villageRepository.findById(v.getId())).thenReturn(Optional.of(v));
    }

    private VillageEntity unlistedVillage() {
        VillageEntity v = village(VILLAGE_ID, VillageVisibility.UNLISTED, null, null);
        villageExists(v);
        return v;
    }

    private VillageMembershipEntity membership(UUID id, UUID villageId, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now().minusDays(10))
                .build();
        m.setId(id);
        return m;
    }

    /** 指定ユーザーを VILLAGE_ID の現役メンバーにする。 */
    private void actorIs(Long userId, VillageRole role) {
        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(
                        VILLAGE_ID, VillageSubjectType.USER, userId))
                .thenReturn(Optional.of(membership(HEADMAN_MEMBERSHIP_ID, VILLAGE_ID, userId, role)));
        lenient().when(membershipRepository
                        .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNullAndBannedAtIsNull(
                                VILLAGE_ID, VillageSubjectType.USER, userId))
                .thenReturn(Optional.of(membership(HEADMAN_MEMBERSHIP_ID, VILLAGE_ID, userId, role)));
    }

    private VillageInvitationEntity invitation(Consumer<VillageInvitationEntity> tweak) {
        VillageInvitationEntity inv = new VillageInvitationEntity();
        inv.setId(INVITATION_ID);
        inv.setVillageId(VILLAGE_ID);
        inv.setTokenHash(sha256Hex(VALID_TOKEN));
        inv.setMaxUses(5);
        inv.setUsedCount(0);
        inv.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        inv.setCreatedByMembershipId(HEADMAN_MEMBERSHIP_ID);
        tweak.accept(inv);
        return inv;
    }

    /** 有効な招待（リンク型・上限5回・24時間後失効）。 */
    private VillageInvitationEntity usableInvitation() {
        return invitation(inv -> { });
    }

    private void tokenResolvesTo(VillageInvitationEntity inv) {
        lenient().when(invitationRepository.findByTokenHash(sha256Hex(VALID_TOKEN)))
                .thenReturn(Optional.of(inv));
        lenient().when(invitationRepository.findByTokenHashForUpdate(sha256Hex(VALID_TOKEN)))
                .thenReturn(Optional.of(inv));
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 が使えない環境は想定外", e);
        }
    }

    /** 受諾で投げられた {@link BusinessException} の ErrorCode を取り出す。 */
    private ErrorCode acceptErrorCode(String token, Long actorUserId) {
        Throwable thrown = catchThrowable(() -> service.accept(token, actorUserId));
        assertThat(thrown)
                .as("受諾は BusinessException で失敗すること（実装が無い間はここで red になる）")
                .isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).getErrorCode();
    }

    /**
     * 「不在トークンへの応答」と完全一致し、かつその値が VILLAGE_NOT_FOUND であることを表明する。
     *
     * <p>一致だけを見ると、両方が同じ内部エラーになっているだけでも緑になってしまう。
     * 必ず絶対値（VILLAGE_001）も見ること。</p>
     */
    private void assertIndistinguishableFromAbsent(String token, Long actorUserId) {
        ErrorCode absent = acceptErrorCode(ABSENT_TOKEN, actorUserId);
        ErrorCode actual = acceptErrorCode(token, actorUserId);

        // (2) 絶対値: 期待した具体値であること
        assertThat(absent).isSameAs(VillageErrorCode.VILLAGE_NOT_FOUND);
        assertThat(actual).isSameAs(VillageErrorCode.VILLAGE_NOT_FOUND);
        assertThat(actual.getCode()).isEqualTo("VILLAGE_001");
        // (1) 一致: コード・メッセージ・深刻度まで不在応答と同一であること
        assertThat(actual.getCode()).isEqualTo(absent.getCode());
        assertThat(actual.getMessage()).isEqualTo(absent.getMessage());
        assertThat(actual.getSeverity()).isEqualTo(absent.getSeverity());
    }

    private VillageInvitationCreateRequest request() {
        return new VillageInvitationCreateRequest(5, 24, null);
    }

    // ==================================================================
    // 発行側
    // ==================================================================

    @Test
    @DisplayName("AC-1: 村長・長老以外の発行は MODERATION_FORBIDDEN(403)")
    void issue_byVillager_moderationForbidden() {
        unlistedVillage();
        actorIs(VILLAGER_ID, VillageRole.VILLAGER);

        Throwable thrown = catchThrowable(() -> service.issue(VILLAGE_ID, VILLAGER_ID, request()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode())
                .isSameAs(VillageErrorCode.MODERATION_FORBIDDEN);
        verify(invitationRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-2: 非メンバーが UNLISTED 村へ発行を試みると VILLAGE_NOT_FOUND(404)。403 ではない")
    void issue_byNonMemberOnUnlistedVillage_notFound() {
        unlistedVillage();

        Throwable thrown = catchThrowable(() -> service.issue(VILLAGE_ID, STRANGER_ID, request()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        ErrorCode code = ((BusinessException) thrown).getErrorCode();
        // 403 に倒すと「その村は実在する」と答えたのと同じになる。必ず不在側のコードであること。
        assertThat(code).isSameAs(VillageErrorCode.VILLAGE_NOT_FOUND);
        assertThat(code).isNotSameAs(VillageErrorCode.MODERATION_FORBIDDEN);
        assertThat(code.getCode()).isEqualTo("VILLAGE_001");
    }

    @Test
    @DisplayName("AC-3: 平文トークンは応答に一度だけ返り、DB には SHA-256 ハッシュのみが保存される")
    void issue_returnsPlaintextOnce_andPersistsOnlyHash() {
        unlistedVillage();
        actorIs(HEADMAN_ID, VillageRole.HEADMAN);
        lenient().when(invitationRepository.save(any(VillageInvitationEntity.class)))
                .thenAnswer(a -> a.getArgument(0));

        VillageInvitationIssueResponse response = service.issue(VILLAGE_ID, HEADMAN_ID, request());

        assertThat(response.token()).as("発行応答は平文トークンを含むこと").isNotBlank();

        ArgumentCaptor<VillageInvitationEntity> captor =
                ArgumentCaptor.forClass(VillageInvitationEntity.class);
        verify(invitationRepository).save(captor.capture());
        VillageInvitationEntity saved = captor.getValue();

        assertThat(saved.getTokenHash()).isEqualTo(sha256Hex(response.token()));
        assertThat(saved.getTokenHash()).isNotEqualTo(response.token());
        assertThat(saved.getTokenHash()).hasSize(64);

        // 「一度だけ」: 一覧には二度と平文が現れない。
        lenient().when(invitationRepository.findByVillageId(VILLAGE_ID)).thenReturn(List.of(saved));
        assertThat(service.list(VILLAGE_ID, HEADMAN_ID))
                .allSatisfy(summary ->
                        assertThat(summary.toString()).doesNotContain(response.token()));
    }

    @Test
    @DisplayName("AC-4: 一覧・失効は自村のもののみ。他村の招待IDを渡すと 404")
    void listAndRevoke_scopedToOwnVillage() {
        unlistedVillage();
        actorIs(HEADMAN_ID, VillageRole.HEADMAN);

        VillageInvitationEntity foreign = new VillageInvitationEntity();
        foreign.setId(OTHER_INVITATION_ID);
        foreign.setVillageId(OTHER_VILLAGE_ID);
        foreign.setTokenHash(sha256Hex("foreign"));
        foreign.setMaxUses(1);
        foreign.setUsedCount(0);
        foreign.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        foreign.setCreatedByMembershipId(HEADMAN_MEMBERSHIP_ID);
        lenient().when(invitationRepository.findById(OTHER_INVITATION_ID))
                .thenReturn(Optional.of(foreign));

        Throwable thrown =
                catchThrowable(() -> service.revoke(VILLAGE_ID, OTHER_INVITATION_ID, HEADMAN_ID));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode())
                .isSameAs(VillageErrorCode.VILLAGE_NOT_FOUND);

        // 一覧には自村の招待だけが並ぶ。
        lenient().when(invitationRepository.findByVillageId(VILLAGE_ID))
                .thenReturn(List.of(usableInvitation()));
        assertThat(service.list(VILLAGE_ID, HEADMAN_ID))
                .extracting(summary -> summary.id())
                .containsExactly(INVITATION_ID)
                .doesNotContain(OTHER_INVITATION_ID);
    }

    @Test
    @DisplayName("AC-5: 失効済みの招待を再度失効しても状態が漏れず冪等に成功する")
    void revoke_isIdempotent() {
        unlistedVillage();
        actorIs(HEADMAN_ID, VillageRole.HEADMAN);
        VillageInvitationEntity revoked = invitation(inv ->
                inv.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS)));
        lenient().when(invitationRepository.findById(INVITATION_ID)).thenReturn(Optional.of(revoked));
        Instant firstRevokedAt = revoked.getRevokedAt();

        Throwable thrown =
                catchThrowable(() -> service.revoke(VILLAGE_ID, INVITATION_ID, HEADMAN_ID));

        assertThat(thrown).as("2度目の失効も例外を投げず成功すること").isNull();
        assertThat(revoked.getRevokedAt())
                .as("既存の失効時刻を上書きしないこと（いつ失効したかの履歴を壊さない）")
                .isEqualTo(firstRevokedAt);
    }

    // ==================================================================
    // 受諾側（秘匿の本丸）
    // ==================================================================

    @Test
    @DisplayName("AC-6: 実在しないトークンでの受諾は VILLAGE_NOT_FOUND(404)")
    void accept_unknownToken_notFound() {
        ErrorCode code = acceptErrorCode(ABSENT_TOKEN, INVITEE_ID);

        assertThat(code).isSameAs(VillageErrorCode.VILLAGE_NOT_FOUND);
        assertThat(code.getCode()).isEqualTo("VILLAGE_001");
    }

    @Nested
    @DisplayName("AC-7: 5つの失敗状態が不在トークンと完全に同一の応答であること")
    class Ac7IndistinguishableStates {

        @Test
        @DisplayName("AC-7a: 期限切れ")
        void expired() {
            unlistedVillage();
            tokenResolvesTo(invitation(inv ->
                    inv.setExpiresAt(Instant.now().minus(1, ChronoUnit.SECONDS))));

            assertIndistinguishableFromAbsent(VALID_TOKEN, INVITEE_ID);
        }

        @Test
        @DisplayName("AC-7b: 使用済み（上限到達）")
        void usedUp() {
            unlistedVillage();
            tokenResolvesTo(invitation(inv -> {
                inv.setMaxUses(3);
                inv.setUsedCount(3);
            }));

            assertIndistinguishableFromAbsent(VALID_TOKEN, INVITEE_ID);
        }

        @Test
        @DisplayName("AC-7c: 失効済み")
        void revoked() {
            unlistedVillage();
            tokenResolvesTo(invitation(inv ->
                    inv.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS))));

            assertIndistinguishableFromAbsent(VALID_TOKEN, INVITEE_ID);
        }

        @Test
        @DisplayName("AC-7d: 村が削除済み")
        void villageDeleted() {
            villageExists(village(VILLAGE_ID, VillageVisibility.UNLISTED,
                    LocalDateTime.now().minusDays(1), null));
            tokenResolvesTo(usableInvitation());

            assertIndistinguishableFromAbsent(VALID_TOKEN, INVITEE_ID);
        }

        @Test
        @DisplayName("AC-7e: 村が凍結済み（409 に倒すと不在と区別がついてしまう）")
        void villageArchived() {
            villageExists(village(VILLAGE_ID, VillageVisibility.UNLISTED,
                    null, LocalDateTime.now().minusDays(1)));
            tokenResolvesTo(usableInvitation());

            assertIndistinguishableFromAbsent(VALID_TOKEN, INVITEE_ID);
        }

        @Test
        @DisplayName("AC-7f 境界: 使用回数がちょうど上限なら不在と同一")
        void boundary_exactlyAtMaxUses() {
            unlistedVillage();
            tokenResolvesTo(invitation(inv -> {
                inv.setMaxUses(5);
                inv.setUsedCount(5);
            }));

            assertIndistinguishableFromAbsent(VALID_TOKEN, INVITEE_ID);
        }

        @Test
        @DisplayName("AC-7g 境界: 使用回数が上限手前なら受諾でき、上限に到達する")
        void boundary_oneBelowMaxUses() {
            unlistedVillage();
            VillageInvitationEntity inv = invitation(i -> {
                i.setMaxUses(5);
                i.setUsedCount(4);
            });
            tokenResolvesTo(inv);
            lenient().when(membershipRepository.save(any(VillageMembershipEntity.class)))
                    .thenAnswer(a -> a.getArgument(0));

            assertThat(service.accept(VALID_TOKEN, INVITEE_ID).villageId()).isEqualTo(VILLAGE_ID);
            assertThat(inv.getUsedCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("AC-7h 境界: 期限ちょうどの瞬間はまだ有効（過ぎて初めて無効）")
        void boundary_exactlyAtExpiry() {
            unlistedVillage();
            tokenResolvesTo(invitation(i -> i.setExpiresAt(Instant.now().plus(1, ChronoUnit.MILLIS))));
            lenient().when(membershipRepository.save(any(VillageMembershipEntity.class)))
                    .thenAnswer(a -> a.getArgument(0));

            assertThat(service.accept(VALID_TOKEN, INVITEE_ID).villageId()).isEqualTo(VILLAGE_ID);
        }
    }

    @Test
    @DisplayName("AC-8: 有効なトークンなら UNLISTED 村でも村人になれる")
    void accept_validToken_joinsUnlistedVillage() {
        unlistedVillage();
        VillageInvitationEntity inv = usableInvitation();
        tokenResolvesTo(inv);
        lenient().when(membershipRepository.save(any(VillageMembershipEntity.class)))
                .thenAnswer(a -> a.getArgument(0));

        VillageInvitationAcceptResponse response = service.accept(VALID_TOKEN, INVITEE_ID);

        assertThat(response.villageId()).isEqualTo(VILLAGE_ID);
        ArgumentCaptor<VillageMembershipEntity> captor =
                ArgumentCaptor.forClass(VillageMembershipEntity.class);
        verify(membershipRepository).save(captor.capture());
        assertThat(captor.getValue().getSubjectId()).isEqualTo(INVITEE_ID);
        assertThat(captor.getValue().getVillageId()).isEqualTo(VILLAGE_ID);
        assertThat(captor.getValue().getRole()).isEqualTo(VillageRole.VILLAGER);
        assertThat(inv.getUsedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9: 指名型招待を指名されていない別人が使うと不在と同一応答")
    void accept_targetedInvitation_byOtherUser_indistinguishable() {
        unlistedVillage();
        tokenResolvesTo(invitation(inv -> inv.setTargetUserId(INVITEE_ID)));

        assertIndistinguishableFromAbsent(VALID_TOKEN, OTHER_INVITEE_ID);
    }

    @Test
    @DisplayName("AC-10: 受諾で invited_by_membership_id に発行者の membership id が記録される")
    void accept_recordsInviterMembershipId() {
        unlistedVillage();
        tokenResolvesTo(usableInvitation());
        lenient().when(membershipRepository.save(any(VillageMembershipEntity.class)))
                .thenAnswer(a -> a.getArgument(0));

        service.accept(VALID_TOKEN, INVITEE_ID);

        ArgumentCaptor<VillageMembershipEntity> captor =
                ArgumentCaptor.forClass(VillageMembershipEntity.class);
        verify(membershipRepository).save(captor.capture());
        assertThat(captor.getValue().getInvitedByMembershipId()).isEqualTo(HEADMAN_MEMBERSHIP_ID);
    }

    // ==================================================================
    // 既存不変条件の非破壊
    // ==================================================================

    @Test
    @DisplayName("AC-12: BAN されたユーザーが招待を使っても MEMBER_BANNED(403) のまま")
    void accept_bannedUser_memberBanned() {
        unlistedVillage();
        tokenResolvesTo(usableInvitation());
        VillageMembershipEntity banned =
                membership(UUID.randomUUID(), VILLAGE_ID, BANNED_ID, VillageRole.VILLAGER);
        banned.setBannedAt(LocalDateTime.now().minusDays(1));
        lenient().when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        VILLAGE_ID, VillageSubjectType.USER, BANNED_ID))
                .thenReturn(Optional.of(banned));

        ErrorCode code = acceptErrorCode(VALID_TOKEN, BANNED_ID);

        // BAN された者は既に村と関係がある＝存在は秘密ではないので、従来どおり 403 のまま。
        assertThat(code).isSameAs(VillageErrorCode.MEMBER_BANNED);
        assertThat(code.getCode()).isEqualTo("VILLAGE_031");
    }

    @Test
    @DisplayName("AC-13: 既に村人が招待を使うと ALREADY_MEMBER(409)")
    void accept_alreadyMember_conflict() {
        unlistedVillage();
        tokenResolvesTo(usableInvitation());
        actorIs(VILLAGER_ID, VillageRole.VILLAGER);
        lenient().when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        VILLAGE_ID, VillageSubjectType.USER, VILLAGER_ID))
                .thenReturn(Optional.of(
                        membership(UUID.randomUUID(), VILLAGE_ID, VILLAGER_ID, VillageRole.VILLAGER)));

        ErrorCode code = acceptErrorCode(VALID_TOKEN, VILLAGER_ID);

        assertThat(code).isSameAs(VillageErrorCode.ALREADY_MEMBER);
        assertThat(code.getCode()).isEqualTo("VILLAGE_006");
    }

    @Test
    @DisplayName("AC-14: 参加上限100村に到達した者は PARTICIPATION_LIMIT_EXCEEDED(429)")
    void accept_participationLimitExceeded() {
        unlistedVillage();
        tokenResolvesTo(usableInvitation());
        List<VillageMembershipEntity> hundred = IntStream.range(0, 100)
                .mapToObj(i -> membership(UUID.randomUUID(), UUID.randomUUID(),
                        INVITEE_ID, VillageRole.VILLAGER))
                .toList();
        lenient().when(membershipRepository.findBySubjectTypeAndSubjectIdAndLeftAtIsNull(
                        VillageSubjectType.USER, INVITEE_ID))
                .thenReturn(hundred);

        ErrorCode code = acceptErrorCode(VALID_TOKEN, INVITEE_ID);

        assertThat(code).isSameAs(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED);
        assertThat(code.getCode()).isEqualTo("VILLAGE_012");
    }

    @Test
    @DisplayName("AC-15: PUBLIC 村の既存挙動（403 / 409 系）が一切変わらない")
    void accept_publicVillage_existingContractUnchanged() {
        villageExists(village(VILLAGE_ID, VillageVisibility.PUBLIC, null, null));
        tokenResolvesTo(usableInvitation());

        // 既村人の受諾 → 409 のまま（UNLISTED 向けの 404 畳み込みが PUBLIC へ波及していないこと）。
        lenient().when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        VILLAGE_ID, VillageSubjectType.USER, VILLAGER_ID))
                .thenReturn(Optional.of(
                        membership(UUID.randomUUID(), VILLAGE_ID, VILLAGER_ID, VillageRole.VILLAGER)));
        assertThat(acceptErrorCode(VALID_TOKEN, VILLAGER_ID))
                .isSameAs(VillageErrorCode.ALREADY_MEMBER);

        // PUBLIC 村の非村人による発行 → 404 ではなく従来どおり 403（存在は秘密ではない）。
        Throwable issueThrown =
                catchThrowable(() -> service.issue(VILLAGE_ID, STRANGER_ID, request()));
        assertThat(issueThrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) issueThrown).getErrorCode())
                .isSameAs(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    // ==================================================================
    // 途中失敗・性能・金庫
    // ==================================================================

    @Test
    @DisplayName("AC-16: 途中で例外が起きても used_count 増加と membership 作成が片方だけ残らない")
    void accept_partialFailure_leavesNoHalfState() throws Exception {
        unlistedVillage();
        tokenResolvesTo(usableInvitation());
        // membership 作成の段で落とす。
        lenient().when(membershipRepository.save(any(VillageMembershipEntity.class)))
                .thenThrow(new DataIntegrityViolationException("同時挿入衝突"));

        Throwable thrown = catchThrowable(() -> service.accept(VALID_TOKEN, INVITEE_ID));

        // 握りつぶさず伝播すること（対処療法の catch を置かせない）。
        assertThat(thrown).isNotNull();
        assertThat(thrown).isNotInstanceOf(UnsupportedOperationException.class);

        // used_count の増加と membership 作成が同一トランザクションに載っていること。
        Method accept = VillageInvitationService.class.getMethod("accept", String.class, Long.class);
        Transactional tx = accept.getAnnotation(Transactional.class);
        assertThat(tx)
                .as("accept は @Transactional であること（used_count と membership を同一 tx に載せる）")
                .isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }

    @Test
    @DisplayName("AC-17: トークン照合は token_hash の単一等値検索のみ（村数・招待数に比例しない）")
    void accept_usesSingleEqualityLookup() {
        unlistedVillage();
        tokenResolvesTo(usableInvitation());
        lenient().when(membershipRepository.save(any(VillageMembershipEntity.class)))
                .thenAnswer(a -> a.getArgument(0));

        service.accept(VALID_TOKEN, INVITEE_ID);

        long byHash = Mockito.mockingDetails(invitationRepository).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("findByTokenHash"))
                .count();
        assertThat(byHash).as("token_hash による等値検索はちょうど1回").isEqualTo(1L);

        // 全件走査・村単位の走査を撃たないこと。
        verify(invitationRepository, never()).findAll();
        verify(invitationRepository, never()).findByVillageId(any());
        verify(villageRepository, times(1)).findById(VILLAGE_ID);
    }

    @Test
    @DisplayName("AC-18: トークンは乱数32バイト由来で、DB を直接読んでも招待リンクを復元できない")
    void issue_tokenIsThirtyTwoRandomBytes_andIrreversible() {
        unlistedVillage();
        actorIs(HEADMAN_ID, VillageRole.HEADMAN);
        lenient().when(invitationRepository.save(any(VillageInvitationEntity.class)))
                .thenAnswer(a -> a.getArgument(0));

        VillageInvitationIssueResponse first = service.issue(VILLAGE_ID, HEADMAN_ID, request());
        VillageInvitationIssueResponse second = service.issue(VILLAGE_ID, HEADMAN_ID, request());

        byte[] decoded = Base64.getUrlDecoder().decode(first.token());
        assertThat(decoded).as("トークンは乱数32バイト由来であること").hasSize(32);
        assertThat(first.token())
                .as("発行のたびに異なる値であること（推測・列挙を許さない）")
                .isNotEqualTo(second.token());

        ArgumentCaptor<VillageInvitationEntity> captor =
                ArgumentCaptor.forClass(VillageInvitationEntity.class);
        verify(invitationRepository, times(2)).save(captor.capture());
        for (VillageInvitationEntity saved : captor.getAllValues()) {
            // DB に載る値から平文は復元できない（SHA-256 hex のみ・平文を含まない）。
            assertThat(saved.getTokenHash()).doesNotContain(first.token());
            assertThat(saved.getTokenHash()).doesNotContain(second.token());
            assertThat(saved.getTokenHash()).matches("[0-9a-f]{64}");
        }
    }
}
