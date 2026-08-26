package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.ChronicleResponse;
import com.mannschaft.app.village.entity.VillageChronicleEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageChronicleRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageChronicleService} 単体テスト（F17.1 Phase 3-β）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>generateForVillage: 新規生成（投稿数・新メンバー数・TOP3 トピック）</li>
 *   <li>generateForVillage: 既存月の再生成は UPSERT で 1 行に集約</li>
 *   <li>generateForVillage: 該当タイトル無しは topics 空・カウント 0</li>
 *   <li>generateForVillage: 削除済村 → 404</li>
 *   <li>getChronicle: 該当無し → 404 CHRONICLE_NOT_FOUND</li>
 *   <li>listChronicles: 年月降順で返却</li>
 *   <li>extractTop3Topics: 単独ヘルパの集計順検証</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageChronicleService 単体テスト")
class VillageChronicleServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000a01");
    private static final LocalDate TARGET_MONTH = LocalDate.of(2026, 4, 1);
    private static final Long ACTOR_USER_ID = 9_810_100L;

    @Mock
    private VillageChronicleRepository chronicleRepository;
    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private BulletinThreadRepository bulletinThreadRepository;
    @Mock
    private TimelinePostRepository timelinePostRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private VillageBulletinAccessService bulletinAccessService;

    @InjectMocks
    private VillageChronicleService service;

    // ========================================================================
    // generateForVillage
    // ========================================================================

    @Test
    @DisplayName("generate: 新規月 → 投稿数・新メンバー数・TOP3 が正しく保存される")
    void generate_new_month() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village()));
        given(bulletinThreadRepository.countByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(12L);
        given(timelinePostRepository.countByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(8L);
        given(membershipRepository.countByVillageIdAndJoinedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(3L);
        given(bulletinThreadRepository.findTitlesByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of(
                        "夏祭り お知らせ",
                        "夏祭り 準備会",
                        "夏祭り 報告",
                        "清掃 案内",
                        "清掃 結果"));
        given(chronicleRepository.findByVillageIdAndYearMonth(VILLAGE_ID, TARGET_MONTH))
                .willReturn(Optional.empty());
        given(chronicleRepository.save(any(VillageChronicleEntity.class)))
                .willAnswer(inv -> {
                    VillageChronicleEntity e = inv.getArgument(0);
                    e.setId(UUID.randomUUID());
                    return e;
                });

        ChronicleResponse res = service.generateForVillage(VILLAGE_ID, TARGET_MONTH);

        ArgumentCaptor<VillageChronicleEntity> cap = ArgumentCaptor.forClass(VillageChronicleEntity.class);
        verify(chronicleRepository).save(cap.capture());
        VillageChronicleEntity saved = cap.getValue();
        assertThat(saved.getPostCount()).isEqualTo(20); // 12 + 8
        assertThat(saved.getNewMemberCount()).isEqualTo(3);
        // TOP1 = "夏祭り" (3 回)、TOP2 = "清掃" (2 回)、TOP3 はその他から 1 件
        assertThat(saved.getTopic1Name()).isEqualTo("夏祭り");
        assertThat(saved.getTopic1Count()).isEqualTo(3);
        assertThat(saved.getTopic2Name()).isEqualTo("清掃");
        assertThat(saved.getTopic2Count()).isEqualTo(2);
        assertThat(saved.getTopic3Name()).isNotNull();
        assertThat(res.postCount()).isEqualTo(20);
        assertThat(res.topics()).hasSize(3);

        verify(auditLogService).record(
                eq(AuditEventType.VILLAGE_CHRONICLE_GENERATED.name()),
                any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("generate: 既存月レコード → 同じ ID を更新（UPSERT）")
    void generate_upsert_existing() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village()));
        given(bulletinThreadRepository.countByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(5L);
        given(timelinePostRepository.countByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(0L);
        given(membershipRepository.countByVillageIdAndJoinedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(1L);
        given(bulletinThreadRepository.findTitlesByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of("お祭り", "お祭り"));
        UUID existingId = UUID.randomUUID();
        VillageChronicleEntity existing = VillageChronicleEntity.builder()
                .villageId(VILLAGE_ID)
                .yearMonth(TARGET_MONTH)
                .postCount(99)
                .newMemberCount(99)
                .topic1Name("OLD")
                .topic1Count(99)
                .build();
        existing.setId(existingId);
        given(chronicleRepository.findByVillageIdAndYearMonth(VILLAGE_ID, TARGET_MONTH))
                .willReturn(Optional.of(existing));
        given(chronicleRepository.save(any(VillageChronicleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        ChronicleResponse res = service.generateForVillage(VILLAGE_ID, TARGET_MONTH);

        // 同じ ID で更新（=UPSERT）。値は最新統計で上書き済み
        assertThat(res.id()).isEqualTo(existingId);
        assertThat(res.postCount()).isEqualTo(5);
        assertThat(res.newMemberCount()).isEqualTo(1);
        assertThat(res.topics()).hasSize(1);
        assertThat(res.topics().get(0).name()).isEqualTo("お祭り");
        assertThat(res.topics().get(0).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("generate: タイトル無し / 投稿 0 → topics 空・カウント 0")
    void generate_no_activity() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(village()));
        given(bulletinThreadRepository.countByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(0L);
        given(timelinePostRepository.countByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(0L);
        given(membershipRepository.countByVillageIdAndJoinedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(0L);
        given(bulletinThreadRepository.findTitlesByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of());
        given(chronicleRepository.findByVillageIdAndYearMonth(VILLAGE_ID, TARGET_MONTH))
                .willReturn(Optional.empty());
        given(chronicleRepository.save(any(VillageChronicleEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        ChronicleResponse res = service.generateForVillage(VILLAGE_ID, TARGET_MONTH);

        assertThat(res.postCount()).isEqualTo(0);
        assertThat(res.newMemberCount()).isEqualTo(0);
        assertThat(res.topics()).isEmpty();
    }

    @Test
    @DisplayName("generate: 削除済村 → 404 VILLAGE_NOT_FOUND")
    void generate_village_deleted() {
        VillageEntity v = village();
        v.setDeletedAt(LocalDateTime.now());
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(v));

        assertThatThrownBy(() -> service.generateForVillage(VILLAGE_ID, TARGET_MONTH))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    // ========================================================================
    // 参照系
    // ========================================================================

    @Test
    @DisplayName("getChronicle: 該当無し → 404 CHRONICLE_NOT_FOUND")
    void get_not_found() {
        given(chronicleRepository.findByVillageIdAndYearMonth(VILLAGE_ID, TARGET_MONTH))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getChronicle(VILLAGE_ID, TARGET_MONTH, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.CHRONICLE_NOT_FOUND);
    }

    @Test
    @DisplayName("listChronicles: Repository から年月降順で返ったものをそのまま DTO 化")
    void list_ordering() {
        VillageChronicleEntity a = chronicle(LocalDate.of(2026, 4, 1), 10, 2);
        VillageChronicleEntity b = chronicle(LocalDate.of(2026, 3, 1), 5, 1);
        given(chronicleRepository.findByVillageIdOrderByYearMonthDesc(VILLAGE_ID))
                .willReturn(List.of(a, b));

        List<ChronicleResponse> res = service.listChronicles(VILLAGE_ID, ACTOR_USER_ID);

        assertThat(res).hasSize(2);
        assertThat(res.get(0).yearMonth()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(res.get(1).yearMonth()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("listChronicles: 掲示板の閲覧認可に委譲し、403 はそのまま伝播する")
    void list_delegatesViewAccessCheck() {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN))
                .given(bulletinAccessService)
                .checkVillageBulletinViewAccess(VILLAGE_ID, ACTOR_USER_ID);

        assertThatThrownBy(() -> service.listChronicles(VILLAGE_ID, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN);

        // 認可 NG なら村史そのものを読みに行かない（漏洩経路を塞ぐ）
        then(chronicleRepository).should(never()).findByVillageIdOrderByYearMonthDesc(VILLAGE_ID);
    }

    @Test
    @DisplayName("getChronicle: 認可は村史の存在確認より先に効く（存在有無も秘匿する）")
    void get_authorizationPrecedesExistenceCheck() {
        willThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN))
                .given(bulletinAccessService)
                .checkVillageBulletinViewAccess(VILLAGE_ID, ACTOR_USER_ID);

        assertThatThrownBy(() -> service.getChronicle(VILLAGE_ID, TARGET_MONTH, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN);

        // CHRONICLE_NOT_FOUND を先に返すと「その月の村史の有無」が非メンバーに漏れる
        then(chronicleRepository).should(never())
                .findByVillageIdAndYearMonth(VILLAGE_ID, TARGET_MONTH);
    }

    // ========================================================================
    // extractTop3Topics
    // ========================================================================

    @Test
    @DisplayName("extractTop3Topics: 頻度降順・同点は辞書順、1 文字トークン除外")
    void extract_top3() {
        List<java.util.Map.Entry<String, Integer>> topics = service.extractTop3Topics(List.of(
                "abc abc def",     // abc=2, def=1
                "abc def ghi",     // abc=3, def=2, ghi=1
                "jkl x y"          // jkl=1, x/y は 1 文字なので除外
        ));

        assertThat(topics).hasSize(3);
        assertThat(topics.get(0).getKey()).isEqualTo("abc");
        assertThat(topics.get(0).getValue()).isEqualTo(3);
        assertThat(topics.get(1).getKey()).isEqualTo("def");
        assertThat(topics.get(1).getValue()).isEqualTo(2);
        // 同点 (ghi=1, jkl=1) は辞書順で ghi が先
        assertThat(topics.get(2).getKey()).isEqualTo("ghi");
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

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

    private VillageChronicleEntity chronicle(LocalDate ym, int postCount, int newMembers) {
        VillageChronicleEntity e = VillageChronicleEntity.builder()
                .villageId(VILLAGE_ID)
                .yearMonth(ym)
                .postCount(postCount)
                .newMemberCount(newMembers)
                .build();
        e.setId(UUID.randomUUID());
        return e;
    }
}
