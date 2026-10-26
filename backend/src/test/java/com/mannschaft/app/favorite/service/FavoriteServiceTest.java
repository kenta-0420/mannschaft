package com.mannschaft.app.favorite.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.FavoriteErrorCode;
import com.mannschaft.app.favorite.dto.FavoriteCheckResultDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.favorite.dto.FavoriteItemDto;
import com.mannschaft.app.favorite.entity.UserFavoriteEntity;
import com.mannschaft.app.favorite.repository.UserFavoriteRepository;
import com.mannschaft.app.favorite.resolver.FavoriteEntityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link FavoriteService} 単体テスト。
 *
 * <p>全てのCRUDメソッドについて正常系・異常系を網羅する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FavoriteService 単体テスト")
class FavoriteServiceTest {

    @Mock
    private UserFavoriteRepository userFavoriteRepository;

    @Mock
    private FavoriteResolverService favoriteResolverService;

    /**
     * TEAMタイプのResolver（addFavoriteでの存在確認用）。
     * List<FavoriteEntityResolver> は @InjectMocks で直接 inject できないため手動生成する。
     */
    @Mock
    private FavoriteEntityResolver teamResolver;

    private FavoriteService favoriteService;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final UUID FAVORITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * 認可ゲートは実物を使う（判定対象のリポジトリと F00 可視性チェッカーはモックを流用する）。
     * お気に入り行の所有者判定は {@code userFavoriteRepository.findById} のままなので、
     * 各テストのスタブはそのまま認可判定に効く。
     */
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @BeforeEach
    void setUp() {
        given(teamResolver.entityType()).willReturn(FavoriteEntityType.TEAM);
        // 既定は「対象が閲覧可能」。閲覧不可のケースは個別テストで false に上書きする。
        given(contentVisibilityChecker.canView(any(), any(), any())).willReturn(true);
        favoriteService = new FavoriteService(userFavoriteRepository, favoriteResolverService,
                List.of(teamResolver),
                new FavoriteAccessGuard(userFavoriteRepository, contentVisibilityChecker));
    }

    // ─────────────────────────────────────────────────────────────────
    // ヘルパーメソッド
    // ─────────────────────────────────────────────────────────────────

    /**
     * テスト用 UserFavoriteEntity を生成するヘルパー。
     */
    private UserFavoriteEntity createEntity(FavoriteEntityType type, String entityId, int order) {
        UserFavoriteEntity e = new UserFavoriteEntity();
        e.setUserId(USER_ID);
        e.setEntityType(type);
        e.setEntityId(entityId);
        e.setDisplayOrder((short) order);
        e.setId(FAVORITE_ID);
        return e;
    }

    /**
     * 利用可能なメタDTOを生成するヘルパー。
     */
    private FavoriteEntityMetaDto availableMeta(String entityId, FavoriteEntityType type) {
        return new FavoriteEntityMetaDto(
                entityId, type, "テストチーム", "/icon.png", "/teams/" + entityId, true, FavoriteEntityStatus.AVAILABLE);
    }

    // ─────────────────────────────────────────────────────────────────
    // getFavorites
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getFavorites")
    class GetFavorites {

        @Test
        @DisplayName("正常系: お気に入りが0件の場合は空リストを返す")
        void getFavorites_0件_空リストを返す() {
            given(userFavoriteRepository.findByUserIdOrderByDisplayOrderAsc(USER_ID))
                    .willReturn(List.of());

            List<FavoriteItemDto> result = favoriteService.getFavorites(USER_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: お気に入りが存在する場合はDTOリストを返す")
        void getFavorites_お気に入りあり_FavoriteItemDtoリストを返す() {
            UserFavoriteEntity entity = createEntity(FavoriteEntityType.TEAM, "1", 0);
            FavoriteEntityMetaDto meta = availableMeta("1", FavoriteEntityType.TEAM);

            given(userFavoriteRepository.findByUserIdOrderByDisplayOrderAsc(USER_ID))
                    .willReturn(List.of(entity));
            given(favoriteResolverService.resolveAll(eq(List.of(entity)), eq(USER_ID)))
                    .willReturn(Map.of("1", meta));

            List<FavoriteItemDto> result = favoriteService.getFavorites(USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).entityType()).isEqualTo(FavoriteEntityType.TEAM);
            assertThat(result.get(0).entityId()).isEqualTo("1");
            assertThat(result.get(0).available()).isTrue();
        }

        @Test
        @DisplayName("正常系: UNAVAILABLE なメタデータがあっても available=false で返す")
        void getFavorites_UNAVAILABLEメタ_available_false_で返す() {
            UserFavoriteEntity entity = createEntity(FavoriteEntityType.TEAM, "999", 0);
            FavoriteEntityMetaDto unavailableMeta = FavoriteEntityMetaDto.unavailable("999", FavoriteEntityType.TEAM);

            given(userFavoriteRepository.findByUserIdOrderByDisplayOrderAsc(USER_ID))
                    .willReturn(List.of(entity));
            given(favoriteResolverService.resolveAll(eq(List.of(entity)), eq(USER_ID)))
                    .willReturn(Map.of("999", unavailableMeta));

            List<FavoriteItemDto> result = favoriteService.getFavorites(USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).available()).isFalse();
            assertThat(result.get(0).displayName()).isNull();
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // addFavorite
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addFavorite")
    class AddFavorite {

        @Test
        @DisplayName("正常系: TEAM追加成功でFavoriteItemDtoを返す")
        void addFavorite_TEAM追加成功_FavoriteItemDtoを返す() {
            String entityId = "1";
            FavoriteEntityMetaDto meta = availableMeta(entityId, FavoriteEntityType.TEAM);

            given(teamResolver.resolveAll(eq(List.of(entityId)), eq(USER_ID)))
                    .willReturn(Map.of(entityId, meta));
            given(userFavoriteRepository.countByUserId(USER_ID)).willReturn(0L);
            UserFavoriteEntity saved = createEntity(FavoriteEntityType.TEAM, entityId, 0);
            given(userFavoriteRepository.save(any())).willReturn(saved);

            FavoriteItemDto result = favoriteService.addFavorite(USER_ID, FavoriteEntityType.TEAM, entityId);

            assertThat(result).isNotNull();
            assertThat(result.entityType()).isEqualTo(FavoriteEntityType.TEAM);
            assertThat(result.entityId()).isEqualTo(entityId);
            assertThat(result.available()).isTrue();
            verify(userFavoriteRepository).incrementAllDisplayOrders(USER_ID);
        }

        @Test
        @DisplayName("異常系: entityType=VILLAGE で entityId が非UUID形式 → FAV_006をスロー")
        void addFavorite_VILLAGE_非UUID_FAV006をスロー() {
            assertThatThrownBy(() -> favoriteService.addFavorite(USER_ID, FavoriteEntityType.VILLAGE, "not-uuid"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_006"));
        }

        @Test
        @DisplayName("異常系: entityType=TEAM で entityId が数値以外 → FAV_006をスロー")
        void addFavorite_TEAM_非数値_FAV006をスロー() {
            assertThatThrownBy(() -> favoriteService.addFavorite(USER_ID, FavoriteEntityType.TEAM, "abc"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_006"));
        }

        @Test
        @DisplayName("異常系: エンティティ不存在（resolverがUNAVAILABLEを返す）→ FAV_003をスロー")
        void addFavorite_エンティティ不存在_FAV003をスロー() {
            String entityId = "999";
            FavoriteEntityMetaDto unavailableMeta = FavoriteEntityMetaDto.unavailable(entityId, FavoriteEntityType.TEAM);

            given(teamResolver.resolveAll(eq(List.of(entityId)), eq(USER_ID)))
                    .willReturn(Map.of(entityId, unavailableMeta));

            assertThatThrownBy(() -> favoriteService.addFavorite(USER_ID, FavoriteEntityType.TEAM, entityId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_003"));
        }

        @Test
        @DisplayName("認可: 閲覧できないチームは FAV_003（404秘匿）で登録できず、上限判定にも進まない")
        void addFavorite_閲覧不可のチーム_FAV003をスロー() {
            String entityId = "1";
            given(contentVisibilityChecker.canView(ReferenceType.TEAM, 1L, USER_ID)).willReturn(false);

            assertThatThrownBy(() -> favoriteService.addFavorite(USER_ID, FavoriteEntityType.TEAM, entityId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_003"));

            // 認可は業務検証より前。件数上限の判定にも保存にも到達しない。
            verify(userFavoriteRepository, never()).countByUserId(any());
            verify(userFavoriteRepository, never()).save(any());
        }

        @Test
        @DisplayName("認可: 閲覧できる組織は登録できる（正常系の非回帰）")
        void addFavorite_閲覧可能な組織_登録できる() {
            String entityId = "5";
            given(contentVisibilityChecker.canView(ReferenceType.ORGANIZATION, 5L, USER_ID)).willReturn(true);
            FavoriteEntityMetaDto meta = availableMeta(entityId, FavoriteEntityType.ORGANIZATION);
            FavoriteEntityResolver orgResolver = org.mockito.Mockito.mock(FavoriteEntityResolver.class);
            given(orgResolver.entityType()).willReturn(FavoriteEntityType.ORGANIZATION);
            given(orgResolver.resolveAll(eq(List.of(entityId)), eq(USER_ID)))
                    .willReturn(Map.of(entityId, meta));
            FavoriteService serviceWithOrg = new FavoriteService(userFavoriteRepository,
                    favoriteResolverService, List.of(teamResolver, orgResolver),
                    new FavoriteAccessGuard(userFavoriteRepository, contentVisibilityChecker));
            given(userFavoriteRepository.countByUserId(USER_ID)).willReturn(0L);
            given(userFavoriteRepository.save(any()))
                    .willReturn(createEntity(FavoriteEntityType.ORGANIZATION, entityId, 0));

            FavoriteItemDto result =
                    serviceWithOrg.addFavorite(USER_ID, FavoriteEntityType.ORGANIZATION, entityId);

            assertThat(result).isNotNull();
            assertThat(result.available()).isTrue();
        }

        @Test
        @DisplayName("異常系: 20件上限を超えている場合 → FAV_002をスロー")
        void addFavorite_上限超過_FAV002をスロー() {
            String entityId = "1";
            FavoriteEntityMetaDto meta = availableMeta(entityId, FavoriteEntityType.TEAM);

            given(teamResolver.resolveAll(eq(List.of(entityId)), eq(USER_ID)))
                    .willReturn(Map.of(entityId, meta));
            given(userFavoriteRepository.countByUserId(USER_ID)).willReturn(20L);

            assertThatThrownBy(() -> favoriteService.addFavorite(USER_ID, FavoriteEntityType.TEAM, entityId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_002"));
        }

        @Test
        @DisplayName("異常系: DataIntegrityViolationException発生時 → FAV_001をスロー")
        void addFavorite_重複登録_FAV001をスロー() {
            String entityId = "1";
            FavoriteEntityMetaDto meta = availableMeta(entityId, FavoriteEntityType.TEAM);

            given(teamResolver.resolveAll(eq(List.of(entityId)), eq(USER_ID)))
                    .willReturn(Map.of(entityId, meta));
            given(userFavoriteRepository.countByUserId(USER_ID)).willReturn(0L);
            given(userFavoriteRepository.save(any())).willThrow(new DataIntegrityViolationException("unique constraint"));

            assertThatThrownBy(() -> favoriteService.addFavorite(USER_ID, FavoriteEntityType.TEAM, entityId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_001"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // removeFavorite
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeFavorite")
    class RemoveFavorite {

        @Test
        @DisplayName("正常系: 削除成功でuserFavoriteRepository.deleteが呼ばれる")
        void removeFavorite_削除成功() {
            UserFavoriteEntity entity = createEntity(FavoriteEntityType.TEAM, "1", 0);

            given(userFavoriteRepository.findById(FAVORITE_ID))
                    .willReturn(Optional.of(entity));

            favoriteService.removeFavorite(USER_ID, FAVORITE_ID);

            verify(userFavoriteRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: IDが存在しない → FAV_003をスロー")
        void removeFavorite_IDが不存在_FAV003をスロー() {
            given(userFavoriteRepository.findById(FAVORITE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> favoriteService.removeFavorite(USER_ID, FAVORITE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_003"));
        }

        @Test
        @DisplayName("異常系: 他ユーザーのお気に入り → FAV_004をスロー")
        void removeFavorite_他ユーザーのお気に入り_FAV004をスロー() {
            UserFavoriteEntity entity = new UserFavoriteEntity();
            entity.setUserId(OTHER_USER_ID);
            entity.setEntityType(FavoriteEntityType.TEAM);
            entity.setEntityId("1");
            entity.setDisplayOrder((short) 0);
            entity.setId(FAVORITE_ID);

            given(userFavoriteRepository.findById(FAVORITE_ID))
                    .willReturn(Optional.of(entity));

            assertThatThrownBy(() -> favoriteService.removeFavorite(USER_ID, FAVORITE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_004"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // reorderFavorites
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("reorderFavorites")
    class ReorderFavorites {

        @Test
        @DisplayName("正常系: 並び替え成功でdisplayOrderが更新される")
        void reorderFavorites_並び替え成功_displayOrder更新() {
            UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

            UserFavoriteEntity e1 = new UserFavoriteEntity();
            e1.setUserId(USER_ID);
            e1.setEntityType(FavoriteEntityType.TEAM);
            e1.setEntityId("1");
            e1.setDisplayOrder((short) 0);
            e1.setId(id1);

            UserFavoriteEntity e2 = new UserFavoriteEntity();
            e2.setUserId(USER_ID);
            e2.setEntityType(FavoriteEntityType.TEAM);
            e2.setEntityId("2");
            e2.setDisplayOrder((short) 1);
            e2.setId(id2);

            given(userFavoriteRepository.findByUserIdOrderByDisplayOrderAsc(USER_ID))
                    .willReturn(List.of(e1, e2));

            // e2を先頭、e1を後に並び替え
            favoriteService.reorderFavorites(USER_ID, List.of(id2, id1));

            assertThat(e2.getDisplayOrder()).isEqualTo((short) 0);
            assertThat(e1.getDisplayOrder()).isEqualTo((short) 1);
            verify(userFavoriteRepository).saveAll(List.of(e1, e2));
        }

        @Test
        @DisplayName("異常系: リストに存在しないIDが含まれる → FAV_003をスロー")
        void reorderFavorites_存在しないIDが含まれる_FAV003をスロー() {
            UUID existingId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID unknownId = UUID.fromString("00000000-0000-0000-0000-000000000099");

            UserFavoriteEntity entity = new UserFavoriteEntity();
            entity.setUserId(USER_ID);
            entity.setEntityType(FavoriteEntityType.TEAM);
            entity.setEntityId("1");
            entity.setDisplayOrder((short) 0);
            entity.setId(existingId);

            given(userFavoriteRepository.findByUserIdOrderByDisplayOrderAsc(USER_ID))
                    .willReturn(List.of(entity));

            assertThatThrownBy(() -> favoriteService.reorderFavorites(USER_ID, List.of(unknownId)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_003"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // getFavoriteById
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getFavoriteById")
    class GetFavoriteById {

        @Test
        @DisplayName("正常系: 取得成功でFavoriteItemDtoを返す")
        void getFavoriteById_取得成功_FavoriteItemDtoを返す() {
            UserFavoriteEntity entity = createEntity(FavoriteEntityType.TEAM, "1", 0);
            FavoriteEntityMetaDto meta = availableMeta("1", FavoriteEntityType.TEAM);

            given(userFavoriteRepository.findById(FAVORITE_ID))
                    .willReturn(Optional.of(entity));
            given(favoriteResolverService.resolveAll(eq(List.of(entity)), eq(USER_ID)))
                    .willReturn(Map.of("1", meta));

            FavoriteItemDto result = favoriteService.getFavoriteById(USER_ID, FAVORITE_ID);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo(FAVORITE_ID);
            assertThat(result.entityType()).isEqualTo(FavoriteEntityType.TEAM);
            assertThat(result.available()).isTrue();
        }

        @Test
        @DisplayName("異常系: IDが存在しない → FAV_003をスロー")
        void getFavoriteById_IDが不存在_FAV003をスロー() {
            given(userFavoriteRepository.findById(FAVORITE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> favoriteService.getFavoriteById(USER_ID, FAVORITE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_003"));
        }

        @Test
        @DisplayName("異常系: 他ユーザーのお気に入り → FAV_004をスロー")
        void getFavoriteById_他ユーザーのお気に入り_FAV004をスロー() {
            UserFavoriteEntity entity = new UserFavoriteEntity();
            entity.setUserId(OTHER_USER_ID);
            entity.setEntityType(FavoriteEntityType.TEAM);
            entity.setEntityId("1");
            entity.setDisplayOrder((short) 0);
            entity.setId(FAVORITE_ID);

            given(userFavoriteRepository.findById(FAVORITE_ID))
                    .willReturn(Optional.of(entity));

            assertThatThrownBy(() -> favoriteService.getFavoriteById(USER_ID, FAVORITE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAV_004"));
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // checkFavorite
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("checkFavorite")
    class CheckFavorite {

        @Test
        @DisplayName("正常系: 登録済み → isFavorited=true と favoriteId を返す")
        void checkFavorite_登録済み_isFavoriteTrueとfavoriteIdを返す() {
            UserFavoriteEntity entity = createEntity(FavoriteEntityType.TEAM, "1", 0);

            given(userFavoriteRepository.findByUserIdAndEntityTypeAndEntityId(
                    USER_ID, FavoriteEntityType.TEAM, "1"))
                    .willReturn(Optional.of(entity));

            FavoriteCheckResultDto result = favoriteService.checkFavorite(
                    USER_ID, FavoriteEntityType.TEAM, "1");

            assertThat(result.isFavorited()).isTrue();
            assertThat(result.favoriteId()).isEqualTo(FAVORITE_ID);
        }

        @Test
        @DisplayName("正常系: 未登録 → isFavorited=false と favoriteId=null を返す")
        void checkFavorite_未登録_isFavoriteFalseとfavoriteIdNullを返す() {
            given(userFavoriteRepository.findByUserIdAndEntityTypeAndEntityId(
                    USER_ID, FavoriteEntityType.TEAM, "999"))
                    .willReturn(Optional.empty());

            FavoriteCheckResultDto result = favoriteService.checkFavorite(
                    USER_ID, FavoriteEntityType.TEAM, "999");

            assertThat(result.isFavorited()).isFalse();
            assertThat(result.favoriteId()).isNull();
        }
    }
}
