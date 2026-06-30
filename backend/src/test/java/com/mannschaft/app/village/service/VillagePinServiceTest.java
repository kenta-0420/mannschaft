package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PinListResponse;
import com.mannschaft.app.village.dto.PinOrderUpdateRequest;
import com.mannschaft.app.village.dto.PinResponse;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link VillagePinService} 単体テスト（F17.1 B8）。
 *
 * <p>設計書 §3.10 / §4.8 に従い以下を検証:</p>
 * <ul>
 *   <li>ピン一覧取得 / sort_order 昇順</li>
 *   <li>ピン追加 / 30 件上限超過 / 重複拒否</li>
 *   <li>ピン解除 / 未登録時 404</li>
 *   <li>並び替え / 不一致検出</li>
 *   <li>自動ピン追加（冪等 / 上限時スキップ）</li>
 *   <li>削除済み / 凍結済み村への操作で 404 VILLAGE_NOT_FOUND</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillagePinService 単体テスト")
class VillagePinServiceTest {

    @Mock
    private UserVillagePinRepository pinRepository;

    @Mock
    private VillageRepository villageRepository;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private VillagePinService pinService;

    private static final Long USER_ID = 700L;

    // ============================================================
    // 一覧取得
    // ============================================================

    @Test
    @DisplayName("listMyPins: sort_order 昇順 + 村名/アイコンを join して返す")
    void listMyPins_ok() {
        UUID vId1 = UUID.randomUUID();
        UUID vId2 = UUID.randomUUID();
        UserVillagePinEntity p1 = pinEntity(vId1, 0L);
        UserVillagePinEntity p2 = pinEntity(vId2, 1L);
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of(p1, p2));
        given(villageRepository.findAllById(any(Iterable.class)))
                .willReturn(List.of(village(vId1, "東村", "icon1"), village(vId2, "西村", "icon2")));
        // villageIconUrl は生 R2 キーでなく署名付き表示 URL を返すこと（画像 404 根治 Phase3）。
        given(mediaUrlResolver.resolve("icon1")).willReturn("https://cdn.example/signed/icon1");
        given(mediaUrlResolver.resolve("icon2")).willReturn("https://cdn.example/signed/icon2");

        PinListResponse res = pinService.listMyPins(USER_ID);

        assertThat(res.count()).isEqualTo(2);
        assertThat(res.maxLimit()).isEqualTo(30);
        assertThat(res.items()).extracting(PinResponse::villageName)
                .containsExactly("東村", "西村");
        assertThat(res.items()).extracting(PinResponse::villageIconUrl)
                .containsExactly("https://cdn.example/signed/icon1", "https://cdn.example/signed/icon2");
    }

    @Test
    @DisplayName("listMyPins: ピン 0 件なら空配列を返す")
    void listMyPins_empty() {
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of());

        PinListResponse res = pinService.listMyPins(USER_ID);

        assertThat(res.count()).isZero();
        assertThat(res.items()).isEmpty();
        assertThat(res.maxLimit()).isEqualTo(30);
    }

    // ============================================================
    // ピン追加
    // ============================================================

    @Test
    @DisplayName("pin: 正常系 → INSERT、sort_order は末尾（既存件数）")
    void pin_ok() {
        UUID vid = UUID.randomUUID();
        given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(vid))
                .willReturn(Optional.of(village(vid, "新村", "icon")));
        given(pinRepository.findByUserIdAndVillageId(USER_ID, vid)).willReturn(Optional.empty());
        given(pinRepository.countByUserId(USER_ID)).willReturn(5L);
        given(pinRepository.saveAndFlush(any(UserVillagePinEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        PinResponse res = pinService.pin(USER_ID, vid);

        ArgumentCaptor<UserVillagePinEntity> captor =
                ArgumentCaptor.forClass(UserVillagePinEntity.class);
        verify(pinRepository).saveAndFlush(captor.capture());
        UserVillagePinEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getVillageId()).isEqualTo(vid);
        assertThat(saved.getSortOrder()).isEqualTo(5L);
        assertThat(res.villageName()).isEqualTo("新村");
    }

    @Test
    @DisplayName("pin: 30 件超過 → 422 VILLAGE_PIN_LIMIT_EXCEEDED")
    void pin_limitExceeded() {
        UUID vid = UUID.randomUUID();
        given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(vid))
                .willReturn(Optional.of(village(vid, "村", "icon")));
        given(pinRepository.findByUserIdAndVillageId(USER_ID, vid)).willReturn(Optional.empty());
        given(pinRepository.countByUserId(USER_ID)).willReturn(30L);

        assertThatThrownBy(() -> pinService.pin(USER_ID, vid))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_PIN_LIMIT_EXCEEDED);

        verify(pinRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("pin: 既にピン済み → 409 VILLAGE_PIN_ALREADY_EXISTS")
    void pin_alreadyExists() {
        UUID vid = UUID.randomUUID();
        given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(vid))
                .willReturn(Optional.of(village(vid, "村", "icon")));
        given(pinRepository.findByUserIdAndVillageId(USER_ID, vid))
                .willReturn(Optional.of(pinEntity(vid, 0L)));

        assertThatThrownBy(() -> pinService.pin(USER_ID, vid))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_PIN_ALREADY_EXISTS);

        verify(pinRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("pin: 村が削除/凍結/不在 → 404 VILLAGE_NOT_FOUND")
    void pin_villageNotFound() {
        UUID vid = UUID.randomUUID();
        given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(vid))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> pinService.pin(USER_ID, vid))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("pin: 同時 POST の race → DataIntegrityViolation を 409 に変換")
    void pin_dbRace() {
        UUID vid = UUID.randomUUID();
        given(villageRepository.findByIdAndDeletedAtIsNullAndArchivedAtIsNull(vid))
                .willReturn(Optional.of(village(vid, "村", "icon")));
        given(pinRepository.findByUserIdAndVillageId(USER_ID, vid)).willReturn(Optional.empty());
        given(pinRepository.countByUserId(USER_ID)).willReturn(0L);
        given(pinRepository.saveAndFlush(any(UserVillagePinEntity.class)))
                .willThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> pinService.pin(USER_ID, vid))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_PIN_ALREADY_EXISTS);
    }

    // ============================================================
    // ピン解除
    // ============================================================

    @Test
    @DisplayName("unpin: 正常系 → delete を呼ぶ")
    void unpin_ok() {
        UUID vid = UUID.randomUUID();
        UserVillagePinEntity p = pinEntity(vid, 0L);
        given(pinRepository.findByUserIdAndVillageId(USER_ID, vid)).willReturn(Optional.of(p));

        pinService.unpin(USER_ID, vid);

        verify(pinRepository).delete(p);
    }

    @Test
    @DisplayName("unpin: 未登録 → 404 VILLAGE_PIN_NOT_FOUND")
    void unpin_notFound() {
        UUID vid = UUID.randomUUID();
        given(pinRepository.findByUserIdAndVillageId(USER_ID, vid)).willReturn(Optional.empty());

        assertThatThrownBy(() -> pinService.unpin(USER_ID, vid))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_PIN_NOT_FOUND);
    }

    // ============================================================
    // 並び替え
    // ============================================================

    @Test
    @DisplayName("reorder: 正常系 → sort_order を 0,1,2,... に振り直す")
    void reorder_ok() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();
        UserVillagePinEntity p1 = pinEntity(v1, 0L);
        UserVillagePinEntity p2 = pinEntity(v2, 1L);
        UserVillagePinEntity p3 = pinEntity(v3, 2L);
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                // 1 回目: 検証用、2 回目: listMyPins 再取得用
                .willReturn(List.of(p1, p2, p3))
                .willReturn(List.of(p3, p1, p2));
        given(villageRepository.findAllById(any(Iterable.class)))
                .willReturn(List.of(
                        village(v1, "一の村", "icon1"),
                        village(v2, "二の村", "icon2"),
                        village(v3, "三の村", "icon3")));

        PinListResponse res = pinService.reorder(USER_ID,
                new PinOrderUpdateRequest(List.of(v3, v1, v2)));

        // 振り直された sort_order を確認
        assertThat(p3.getSortOrder()).isZero();
        assertThat(p1.getSortOrder()).isEqualTo(1L);
        assertThat(p2.getSortOrder()).isEqualTo(2L);
        verify(pinRepository).saveAll(anyIterable());
        assertThat(res.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("reorder: 集合が不一致（要素過不足） → 422 VILLAGE_PIN_ORDER_MISMATCH")
    void reorder_mismatch_missing() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of(pinEntity(v1, 0L), pinEntity(v2, 1L)));

        // v3 は現在のピンに存在しない
        assertThatThrownBy(() -> pinService.reorder(USER_ID,
                new PinOrderUpdateRequest(List.of(v1, v3))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_PIN_ORDER_MISMATCH);

        verify(pinRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("reorder: 重複 ID 含む → 422 VILLAGE_PIN_ORDER_MISMATCH")
    void reorder_mismatch_duplicate() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of(pinEntity(v1, 0L), pinEntity(v2, 1L)));

        assertThatThrownBy(() -> pinService.reorder(USER_ID,
                new PinOrderUpdateRequest(List.of(v1, v1))))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.VILLAGE_PIN_ORDER_MISMATCH);

        verify(pinRepository, never()).saveAll(any());
    }

    // ============================================================
    // 自動ピン
    // ============================================================

    @Test
    @DisplayName("autoPinOnJoin: 正常系 → INSERT")
    void autoPinOnJoin_ok() {
        UUID vid = UUID.randomUUID();
        given(pinRepository.findByUserIdAndVillageId(USER_ID, vid)).willReturn(Optional.empty());
        given(pinRepository.countByUserId(USER_ID)).willReturn(0L);

        pinService.autoPinOnJoin(USER_ID, vid);

        ArgumentCaptor<UserVillagePinEntity> captor =
                ArgumentCaptor.forClass(UserVillagePinEntity.class);
        verify(pinRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getVillageId()).isEqualTo(vid);
        assertThat(captor.getValue().getSortOrder()).isZero();
    }

    @Test
    @DisplayName("autoPinOnJoin: 既にピン済み → 何もしない（冪等）")
    void autoPinOnJoin_idempotent() {
        UUID vid = UUID.randomUUID();
        given(pinRepository.findByUserIdAndVillageId(USER_ID, vid))
                .willReturn(Optional.of(pinEntity(vid, 0L)));

        pinService.autoPinOnJoin(USER_ID, vid);

        verify(pinRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("autoPinOnJoin: 上限超過 → 例外を投げず黙ってスキップ")
    void autoPinOnJoin_skipOnLimit() {
        UUID vid = UUID.randomUUID();
        given(pinRepository.findByUserIdAndVillageId(USER_ID, vid)).willReturn(Optional.empty());
        given(pinRepository.countByUserId(USER_ID)).willReturn(30L);

        // 例外を投げないことが要件（参加処理を阻害しないため）
        pinService.autoPinOnJoin(USER_ID, vid);

        verify(pinRepository, never()).saveAndFlush(any());
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private UserVillagePinEntity pinEntity(UUID villageId, long sortOrder) {
        UserVillagePinEntity e = UserVillagePinEntity.builder()
                .userId(USER_ID)
                .villageId(villageId)
                .sortOrder(sortOrder)
                .pinnedAt(LocalDateTime.now())
                .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    private VillageEntity village(UUID id, String name, String iconKey) {
        VillageEntity v = VillageEntity.builder()
                .slug("slug-" + name)
                .name(name)
                .iconR2Key(iconKey)
                .memberCountCache(1L)
                .build();
        v.setId(id);
        return v;
    }

    /** unused サプレス用（将来テスト追加時の足場）。 */
    @SuppressWarnings("unused")
    private List<UserVillagePinEntity> newList() {
        return new ArrayList<>();
    }
}
