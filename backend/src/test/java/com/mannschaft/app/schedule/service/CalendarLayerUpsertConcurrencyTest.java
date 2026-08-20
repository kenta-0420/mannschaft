package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.dto.CalendarLayerResponse;
import com.mannschaft.app.schedule.dto.CalendarLayerUpdateRequest;
import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import com.mannschaft.app.schedule.repository.UserCalendarLayerSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * F03.19 — レイヤー設定 PATCH の <b>upsert 原子性</b>の回帰テスト（単体・{@code REPEATABLE READ} 模倣）。
 *
 * <h2>塞ぐ欠陥</h2>
 * <p>設定行がまだ無い同一レイヤーへ PATCH が<b>並行して 2 件</b>来ると、
 * 「{@code findBy...} が空 → 新規 Entity を {@code save}」という検査と書き込みの隙間で
 * 両方が新規行を作ろうとし、後着が {@code uk_user_calendar_layer} 違反で 500 を返していた。</p>
 *
 * <p>その一次修正（{@code INSERT IGNORE} ＋ 0 件なら通常 SELECT で取り直し）は
 * <b>実運用の分離レベルでは成立しない</b>。実 DB の
 * {@code @@global.transaction_isolation} / {@code @@session.transaction_isolation} はいずれも
 * {@code REPEATABLE-READ} であり、通常の SELECT はトランザクション冒頭に張ったスナップショットを
 * 見続けるため、{@code INSERT IGNORE} が 0 を返した後に取り直しても
 * <b>先着がコミットした行は見えない</b> → {@code IllegalStateException} → 500 のままだった。</p>
 *
 * <h2>この単体テストがどうやって分離レベルを再現するか</h2>
 * <p>Repository のモックに<b>スナップショット分離を持たせる</b>。
 * すなわちインメモリのデータ集合を「コミット済みの現在値（{@code committed}）」と
 * 「本トランザクションが最初の読み取り時に写し取ったスナップショット（{@code snapshot}）」に分け、</p>
 * <ul>
 *   <li>通常の {@code findByUserIdAndScopeTypeAndScopeId} は<b>スナップショット</b>を読む
 *       （ただし自トランザクションの書き込みは見える — InnoDB と同じ）</li>
 *   <li>{@code insertIfAbsent}（{@code INSERT IGNORE}）は<b>現在値</b>に対して働く
 *       （書き込みは常に最新を見るため）</li>
 *   <li>{@code findForUpdateBy...}（{@code SELECT ... FOR UPDATE}）は
 *       ロック付き読み取り＝<b>現在読み取り</b>なので<b>現在値</b>を読む</li>
 * </ul>
 * <p>この模倣の下では、通常 SELECT で取り直す実装は必ず赤くなり、
 * ロック付き読み取りで取り直す実装だけが緑になる。
 * 実 DB での裏取りは {@code CalendarLayerUpsertConcurrencyIT}
 * （Testcontainers・実 MySQL・実 2 トランザクション）が行う。</p>
 */
@DisplayName("F03.19 レイヤー設定 PATCH の upsert 原子性（REPEATABLE READ 下で並行 PATCH を 500 にしない）")
class CalendarLayerUpsertConcurrencyTest {

    private static final Long ME = 1001L;
    private static final Long MY_TEAM = 42L;

    /** コミット済みの現在値（{@code uk_user_calendar_layer} をキーに持つ）。 */
    private Map<String, UserCalendarLayerSettingEntity> committed;
    /** 本トランザクションが最初の読み取りで写し取ったスナップショット（未確立なら null）。 */
    private Map<String, UserCalendarLayerSettingEntity> snapshot;
    /** 本トランザクション自身が書いたキー（自分の書き込みは常に見える）。 */
    private Set<String> writtenByMe;

    private UserCalendarLayerSettingRepository repository;
    private AccessControlService accessControlService;
    private NameResolverService nameResolverService;
    private CalendarLayerService service;

    @BeforeEach
    void setUp() {
        committed = new LinkedHashMap<>();
        snapshot = null;
        writtenByMe = new HashSet<>();

        repository = mock(UserCalendarLayerSettingRepository.class);
        accessControlService = mock(AccessControlService.class);
        nameResolverService = mock(NameResolverService.class);

        lenient().when(accessControlService.findAffiliatedScopeIds(ME, "TEAM"))
                .thenReturn(new LinkedHashSet<>(List.of(MY_TEAM)));
        lenient().when(nameResolverService.resolveScopeName(anyString(), anyLong()))
                .thenReturn("青葉FC");
        lenient().when(nameResolverService.resolveIconUrl(anyString(), anyLong()))
                .thenReturn(null);

        // 通常 SELECT = 一貫性読み取り（スナップショット）。最初の読み取りでスナップショットが張られる。
        lenient().doAnswer(inv -> {
            String k = key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            establishSnapshot();
            return Optional.ofNullable(readSnapshot(k));
        }).when(repository).findByUserIdAndScopeTypeAndScopeId(any(), anyString(), any());

        // SELECT ... FOR UPDATE = 現在読み取り。スナップショットではなく最新のコミット済みを読む。
        lenient().doAnswer(inv -> {
            String k = key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return Optional.ofNullable(committed.get(k));
        }).when(repository).findForUpdateByUserIdAndScopeTypeAndScopeId(any(), anyString(), any());

        lenient().when(repository.countByUserId(any())).thenAnswer(inv -> {
            establishSnapshot();
            return (long) snapshot.size();
        });

        // INSERT IGNORE の意味論: 書き込みは現在値を見る。既にキーがあれば 0 件・例外なし。
        lenient().doAnswer(inv -> {
            byte[] idBytes = inv.getArgument(0);
            Long userId = inv.getArgument(1);
            String scopeType = inv.getArgument(2);
            Long scopeId = inv.getArgument(3);
            String k = key(userId, scopeType, scopeId);
            if (committed.containsKey(k)) {
                return 0;
            }
            UserCalendarLayerSettingEntity created = row(userId, scopeType, scopeId, null, false);
            created.setId(toUuid(idBytes));
            committed.put(k, created);
            writtenByMe.add(k);
            return 1;
        }).when(repository).insertIfAbsent(any(), any(), anyString(), any());

        // save の意味論: 未採番（新規）行が既存キーと衝突したら実 DB と同じく制約違反で落ちる。
        lenient().doAnswer(inv -> {
            UserCalendarLayerSettingEntity e = inv.getArgument(0);
            String k = key(e.getUserId(), e.getScopeType(), e.getScopeId());
            UserCalendarLayerSettingEntity current = committed.get(k);
            if (e.getId() == null) {
                if (current != null) {
                    throw new DataIntegrityViolationException(
                            "Duplicate entry for key uk_user_calendar_layer");
                }
                e.setId(UUID.randomUUID());
            }
            committed.put(k, e);
            writtenByMe.add(k);
            return e;
        }).when(repository).save(any(UserCalendarLayerSettingEntity.class));

        service = new CalendarLayerService(repository, accessControlService, nameResolverService);
    }

    @Test
    @DisplayName("〔陽性〕REPEATABLE READ 下で先着が行を作っても 500 にならず冪等に確定する")
    void concurrentPatch_underRepeatableRead_doesNotFail() {
        // 本トランザクションのスナップショットを「行が無い」時点で確立する。
        assertThat(repository.findByUserIdAndScopeTypeAndScopeId(ME, "TEAM", MY_TEAM)).isEmpty();

        // 先着の別トランザクションが同じ行を作ってコミットする。
        // REPEATABLE READ なので、本トランザクションの通常 SELECT には最後まで見えない。
        UserCalendarLayerSettingEntity rival = row(ME, "TEAM", MY_TEAM, "#059669", true);
        rival.setId(UUID.randomUUID());
        committed.put(key(ME, "TEAM", MY_TEAM), rival);

        CalendarLayerResponse[] result = new CalendarLayerResponse[1];
        assertThatCode(() -> result[0] = service.updateLayer(
                ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#dc2626", null)))
                .doesNotThrowAnyException();

        // 冪等: 行は 1 本だけ・自分の指定色が載り、送っていない hidden は先着の値を壊さない。
        assertThat(committed).hasSize(1);
        assertThat(result[0].color()).isEqualTo("#DC2626");
        assertThat(committed.get(key(ME, "TEAM", MY_TEAM)).getColor()).isEqualTo("#DC2626");
        assertThat(committed.get(key(ME, "TEAM", MY_TEAM)).getHidden()).isTrue();
    }

    @Test
    @DisplayName("〔陽性〕先着が居なければ新規行が作られる（陽性対照）")
    void patch_whenNoRival_createsRow() {
        CalendarLayerResponse response = service.updateLayer(
                ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", true));

        assertThat(committed).hasSize(1);
        assertThat(response.color()).isEqualTo("#DC2626");
        assertThat(response.hidden()).isTrue();
    }

    @Test
    @DisplayName("〔冪等〕同じ PATCH を 2 回送っても行は 1 本のまま・結果が同じ")
    void repeatedPatch_isIdempotent() {
        CalendarLayerResponse first = service.updateLayer(
                ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", true));
        CalendarLayerResponse second = service.updateLayer(
                ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", true));

        assertThat(committed).hasSize(1);
        assertThat(second.color()).isEqualTo(first.color());
        assertThat(second.hidden()).isEqualTo(first.hidden());
    }

    // ------------------------------------------------------------------
    // スナップショット分離の模倣
    // ------------------------------------------------------------------

    /** 最初の読み取りでコミット済み現在値を写し取る（以後この写しが見え続ける）。 */
    private void establishSnapshot() {
        if (snapshot == null) {
            snapshot = new LinkedHashMap<>(committed);
        }
    }

    /** スナップショットを読む。ただし自トランザクションの書き込みだけは最新が見える。 */
    private UserCalendarLayerSettingEntity readSnapshot(String k) {
        if (writtenByMe.contains(k)) {
            return committed.get(k);
        }
        return snapshot.get(k);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String key(Object userId, Object scopeType, Object scopeId) {
        return userId + "/" + scopeType + "/" + scopeId;
    }

    private static UserCalendarLayerSettingEntity row(Long userId, String scopeType, Long scopeId,
                                                      String color, boolean hidden) {
        return UserCalendarLayerSettingEntity.builder()
                .userId(userId).scopeType(scopeType).scopeId(scopeId)
                .color(color).hidden(hidden)
                .build();
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
