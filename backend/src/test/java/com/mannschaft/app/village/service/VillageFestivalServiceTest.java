package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.FestivalCreateRequest;
import com.mannschaft.app.village.dto.FestivalResponse;
import com.mannschaft.app.village.dto.FestivalUpdateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageFestivalService} 単体テスト（F17.1 Phase 2 U5）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>create: 正常系（SCHEDULED 初期化 / ACTIVE 即時化）</li>
 *   <li>create: 権限エラー（村人ではない / VILLAGER しか持たない）</li>
 *   <li>create: 期間不正 / 過去終了 / 色フォーマット不正</li>
 *   <li>create: 削除済 / 凍結済の村 → エラー</li>
 *   <li>update: 部分更新 / ENDED は不可</li>
 *   <li>cancel: 正常系 / 既に CANCELLED は冪等</li>
 *   <li>list / get: 正常系</li>
 *   <li>get: 村違い → 404 IDOR 防止</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageFestivalService 単体テスト")
class VillageFestivalServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000601");
    private static final UUID OTHER_VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000602");
    private static final UUID FESTIVAL_ID = UUID.fromString("01956c00-0000-7000-8000-000000000701");
    private static final Long ACTOR_USER_ID = 901L;

    @Mock
    private VillageFestivalRepository festivalRepository;
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private VillageFestivalService service;

    @BeforeEach
    void setUp() {
        // 既定: 村は有効、actorは HEADMAN とする（個別テストで上書き可）
    }

    // ========================================================================
    // create
    // ========================================================================

    @Test
    @DisplayName("create: 未来の祭り → SCHEDULED で保存・監査ログ記録")
    void create_future_scheduled() {
        givenActiveVillage();
        givenActorRole(VillageRole.HEADMAN);
        LocalDateTime starts = LocalDateTime.now().plusDays(7);
        LocalDateTime ends = starts.plusDays(2);
        FestivalCreateRequest req = new FestivalCreateRequest(
                "夏祭り", "盛大に", starts, ends, "village/X/festival/Y/banner.png", "#FF8800");
        given(festivalRepository.save(any(VillageFestivalEntity.class)))
                .willAnswer(inv -> {
                    VillageFestivalEntity e = inv.getArgument(0);
                    e.setId(FESTIVAL_ID);
                    e.setCreatedAt(LocalDateTime.now());
                    return e;
                });

        FestivalResponse res = service.createFestival(VILLAGE_ID, req, ACTOR_USER_ID);

        ArgumentCaptor<VillageFestivalEntity> cap = ArgumentCaptor.forClass(VillageFestivalEntity.class);
        verify(festivalRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(VillageFestivalStatus.SCHEDULED);
        assertThat(cap.getValue().getCreatedByUserId()).isEqualTo(ACTOR_USER_ID);
        assertThat(res.status()).isEqualTo(VillageFestivalStatus.SCHEDULED);
        assertThat(res.title()).isEqualTo("夏祭り");
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_FESTIVAL_CREATED.name()),
                eq(ACTOR_USER_ID), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("create: 既に開始時刻を過ぎている祭り → ACTIVE 初期化")
    void create_already_started_active() {
        givenActiveVillage();
        givenActorRole(VillageRole.ELDER);
        LocalDateTime starts = LocalDateTime.now().minusHours(1);
        LocalDateTime ends = LocalDateTime.now().plusDays(1);
        FestivalCreateRequest req = new FestivalCreateRequest("即時祭", null, starts, ends, null, null);
        given(festivalRepository.save(any(VillageFestivalEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        FestivalResponse res = service.createFestival(VILLAGE_ID, req, ACTOR_USER_ID);

        assertThat(res.status()).isEqualTo(VillageFestivalStatus.ACTIVE);
    }

    @Test
    @DisplayName("create: 既に終了時刻も過ぎている祭り → 422 FESTIVAL_INVALID_PERIOD")
    void create_already_ended_rejected() {
        givenActiveVillage();
        givenActorRole(VillageRole.HEADMAN);
        LocalDateTime starts = LocalDateTime.now().minusDays(3);
        LocalDateTime ends = LocalDateTime.now().minusDays(1);
        FestivalCreateRequest req = new FestivalCreateRequest("過去祭", null, starts, ends, null, null);

        assertThatThrownBy(() -> service.createFestival(VILLAGE_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.FESTIVAL_INVALID_PERIOD);
        verify(festivalRepository, never()).save(any());
    }

    @Test
    @DisplayName("create: starts_at >= ends_at → 422 FESTIVAL_INVALID_PERIOD")
    void create_invalid_period() {
        givenActiveVillage();
        givenActorRole(VillageRole.HEADMAN);
        LocalDateTime t = LocalDateTime.now().plusDays(1);
        FestivalCreateRequest req = new FestivalCreateRequest("逆転祭", null, t, t, null, null);

        assertThatThrownBy(() -> service.createFestival(VILLAGE_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.FESTIVAL_INVALID_PERIOD);
    }

    @Test
    @DisplayName("create: テーマ色不正 → 422 FESTIVAL_INVALID_COLOR")
    void create_invalid_color() {
        givenActiveVillage();
        givenActorRole(VillageRole.HEADMAN);
        LocalDateTime starts = LocalDateTime.now().plusDays(1);
        LocalDateTime ends = starts.plusDays(1);
        FestivalCreateRequest req = new FestivalCreateRequest("祭", null, starts, ends, null, "red");

        assertThatThrownBy(() -> service.createFestival(VILLAGE_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.FESTIVAL_INVALID_COLOR);
    }

    @Test
    @DisplayName("create: 村人でない → 403 MODERATION_FORBIDDEN")
    void create_not_member_forbidden() {
        givenActiveVillage();
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.empty());
        LocalDateTime starts = LocalDateTime.now().plusDays(1);
        FestivalCreateRequest req = new FestivalCreateRequest("祭", null, starts, starts.plusDays(1), null, null);

        assertThatThrownBy(() -> service.createFestival(VILLAGE_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    @Test
    @DisplayName("create: 一般 VILLAGER → 403 MODERATION_FORBIDDEN")
    void create_villager_forbidden() {
        givenActiveVillage();
        givenActorRole(VillageRole.VILLAGER);
        LocalDateTime starts = LocalDateTime.now().plusDays(1);
        FestivalCreateRequest req = new FestivalCreateRequest("祭", null, starts, starts.plusDays(1), null, null);

        assertThatThrownBy(() -> service.createFestival(VILLAGE_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.MODERATION_FORBIDDEN);
    }

    @Test
    @DisplayName("create: 村が論理削除済 → 404 VILLAGE_NOT_FOUND")
    void create_village_deleted() {
        VillageEntity v = village();
        v.setDeletedAt(LocalDateTime.now());
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(v));
        LocalDateTime starts = LocalDateTime.now().plusDays(1);
        FestivalCreateRequest req = new FestivalCreateRequest("祭", null, starts, starts.plusDays(1), null, null);

        assertThatThrownBy(() -> service.createFestival(VILLAGE_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    // ========================================================================
    // update
    // ========================================================================

    @Test
    @DisplayName("update: 部分更新（title のみ）→ 既存値は維持される")
    void update_partial_title() {
        givenActiveVillage();
        givenActorRole(VillageRole.HEADMAN);
        VillageFestivalEntity existing = festival(VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(7));
        given(festivalRepository.findById(FESTIVAL_ID)).willReturn(Optional.of(existing));
        given(festivalRepository.save(any(VillageFestivalEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        FestivalUpdateRequest req = new FestivalUpdateRequest("新タイトル", null, null, null, null, null);
        FestivalResponse res = service.updateFestival(VILLAGE_ID, FESTIVAL_ID, req, ACTOR_USER_ID);

        assertThat(res.title()).isEqualTo("新タイトル");
        // 期間は維持
        assertThat(res.startsAt()).isEqualTo(existing.getStartsAt());
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_FESTIVAL_UPDATED.name()),
                eq(ACTOR_USER_ID), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("update: ENDED の祭り → 409 FESTIVAL_ALREADY_ENDED")
    void update_already_ended() {
        givenActiveVillage();
        givenActorRole(VillageRole.HEADMAN);
        VillageFestivalEntity existing = festival(VillageFestivalStatus.ENDED,
                LocalDateTime.now().minusDays(5), LocalDateTime.now().minusDays(3));
        given(festivalRepository.findById(FESTIVAL_ID)).willReturn(Optional.of(existing));

        FestivalUpdateRequest req = new FestivalUpdateRequest("変更", null, null, null, null, null);
        assertThatThrownBy(() -> service.updateFestival(VILLAGE_ID, FESTIVAL_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.FESTIVAL_ALREADY_ENDED);
        verify(festivalRepository, never()).save(any());
    }

    @Test
    @DisplayName("update: starts_at だけ更新 → 既存 ends_at と比較して整合性検証")
    void update_starts_at_consistency() {
        givenActiveVillage();
        givenActorRole(VillageRole.HEADMAN);
        VillageFestivalEntity existing = festival(VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(7));
        given(festivalRepository.findById(FESTIVAL_ID)).willReturn(Optional.of(existing));

        // ends_at(+7日) より後の starts_at(+10日) は不正
        FestivalUpdateRequest req = new FestivalUpdateRequest(
                null, null, LocalDateTime.now().plusDays(10), null, null, null);
        assertThatThrownBy(() -> service.updateFestival(VILLAGE_ID, FESTIVAL_ID, req, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.FESTIVAL_INVALID_PERIOD);
    }

    // ========================================================================
    // cancel
    // ========================================================================

    @Test
    @DisplayName("cancel: SCHEDULED → CANCELLED に遷移し監査ログ記録")
    void cancel_ok() {
        givenActiveVillage();
        givenActorRole(VillageRole.HEADMAN);
        VillageFestivalEntity existing = festival(VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(7));
        given(festivalRepository.findById(FESTIVAL_ID)).willReturn(Optional.of(existing));
        given(festivalRepository.save(any(VillageFestivalEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        FestivalResponse res = service.cancelFestival(VILLAGE_ID, FESTIVAL_ID, ACTOR_USER_ID);

        assertThat(res.status()).isEqualTo(VillageFestivalStatus.CANCELLED);
        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_FESTIVAL_CANCELLED.name()),
                eq(ACTOR_USER_ID), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("cancel: 既に CANCELLED → 冪等 no-op（監査ログ無し）")
    void cancel_idempotent() {
        givenActiveVillage();
        givenActorRole(VillageRole.HEADMAN);
        VillageFestivalEntity existing = festival(VillageFestivalStatus.CANCELLED,
                LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(7));
        given(festivalRepository.findById(FESTIVAL_ID)).willReturn(Optional.of(existing));

        FestivalResponse res = service.cancelFestival(VILLAGE_ID, FESTIVAL_ID, ACTOR_USER_ID);

        assertThat(res.status()).isEqualTo(VillageFestivalStatus.CANCELLED);
        verify(festivalRepository, never()).save(any());
        verify(auditLogService, never()).record(
                anyString(), any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    // ========================================================================
    // list / get
    // ========================================================================

    @Test
    @DisplayName("list: status 指定なし → 全件取得")
    void list_all() {
        givenActiveVillage();
        VillageFestivalEntity f1 = festival(VillageFestivalStatus.SCHEDULED,
                LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(6));
        VillageFestivalEntity f2 = festival(VillageFestivalStatus.ACTIVE,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1));
        Page<VillageFestivalEntity> page = new PageImpl<>(List.of(f1, f2));
        given(festivalRepository.findByVillageIdAndDeletedAtIsNull(eq(VILLAGE_ID), any(Pageable.class)))
                .willReturn(page);

        List<FestivalResponse> res = service.listFestivals(VILLAGE_ID, null, null);

        assertThat(res).hasSize(2);
    }

    @Test
    @DisplayName("list: status=ACTIVE → status 別取得 API を呼ぶ")
    void list_filtered_by_status() {
        givenActiveVillage();
        VillageFestivalEntity f = festival(VillageFestivalStatus.ACTIVE,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1));
        Page<VillageFestivalEntity> page = new PageImpl<>(List.of(f));
        given(festivalRepository.findByVillageIdAndStatusAndDeletedAtIsNull(
                eq(VILLAGE_ID), eq(VillageFestivalStatus.ACTIVE), any(Pageable.class)))
                .willReturn(page);

        List<FestivalResponse> res = service.listFestivals(VILLAGE_ID, VillageFestivalStatus.ACTIVE, null);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).status()).isEqualTo(VillageFestivalStatus.ACTIVE);
    }

    @Test
    @DisplayName("get: 村が違う祭り ID → 404 FESTIVAL_NOT_FOUND（IDOR 防止）")
    void get_idor_protection() {
        givenActiveVillage();
        VillageFestivalEntity wrong = festival(VillageFestivalStatus.ACTIVE,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1));
        wrong.setVillageId(OTHER_VILLAGE_ID); // 別の村のもの
        given(festivalRepository.findById(FESTIVAL_ID)).willReturn(Optional.of(wrong));

        assertThatThrownBy(() -> service.getFestival(VILLAGE_ID, FESTIVAL_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.FESTIVAL_NOT_FOUND);
    }

    @Test
    @DisplayName("get: 存在しない festival → 404 FESTIVAL_NOT_FOUND")
    void get_not_found() {
        givenActiveVillage();
        given(festivalRepository.findById(FESTIVAL_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFestival(VILLAGE_ID, FESTIVAL_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.FESTIVAL_NOT_FOUND);
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private void givenActiveVillage() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village()));
    }

    private void givenActorRole(VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(ACTOR_USER_ID)
                .role(role)
                .joinedAt(LocalDateTime.now().minusDays(10))
                .build();
        m.setId(UUID.randomUUID());
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(m));
    }

    private VillageEntity village() {
        VillageEntity v = VillageEntity.builder()
                .slug("test-village")
                .name("テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(10L)
                .build();
        v.setId(VILLAGE_ID);
        return v;
    }

    private VillageFestivalEntity festival(VillageFestivalStatus status, LocalDateTime starts, LocalDateTime ends) {
        VillageFestivalEntity e = VillageFestivalEntity.builder()
                .villageId(VILLAGE_ID)
                .title("既存タイトル")
                .description("既存説明")
                .startsAt(starts)
                .endsAt(ends)
                .status(status)
                .createdByUserId(ACTOR_USER_ID)
                .build();
        e.setId(FESTIVAL_ID);
        e.setCreatedAt(LocalDateTime.now().minusDays(1));
        return e;
    }
}
