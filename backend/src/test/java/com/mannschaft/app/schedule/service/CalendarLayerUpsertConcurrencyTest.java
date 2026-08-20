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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * F03.19 — レイヤー設定 PATCH の <b>upsert 原子性</b>の回帰テスト。
 *
 * <h2>塞ぐ欠陥</h2>
 * <p>設定行がまだ無い同一レイヤーへ PATCH が<b>並行して 2 件</b>来ると、
 * 「{@code findBy...} が空 → 新規 Entity を {@code save}」という検査と書き込みの隙間で
 * 両方が新規行を作ろうとし、後着が {@code uk_user_calendar_layer}
 * （{@code user_id, scope_type, scope_id}）違反で 500 を返していた。
 * PATCH の再送・二重操作で起き、<b>upsert・冪等という API 契約を破る</b>。</p>
 *
 * <h2>この単体テストがどうやって「並行」を再現するか</h2>
 * <p>Repository のモックにインメモリの実データ集合を持たせ、
 * <b>ユニーク制約まで忠実に再現</b>する（{@code save} で未採番の新規行のキーが既存と衝突したら
 * 実 DB と同じく {@link DataIntegrityViolationException} を投げる）。
 * そのうえで「{@code findBy...} の <b>1 回目の呼び出しが空を返した直後に</b>、
 * 別リクエストが同じ行を作る」という割り込みを {@code Answer} で挟み込む。
 * これは実 DB で起きる TOCTOU そのものであり、スレッドを起こさずに決定的に再現できる。</p>
 *
 * <p>修正前はここで {@code DataIntegrityViolationException}（＝ 500）が漏れて赤くなる。
 * 修正後は {@code INSERT IGNORE}（{@code insertIfAbsent}）が競合を例外化せず、
 * 0 件挿入なら相手の作った行を取り直して更新に回すため、結果は冪等に確定する。</p>
 */
@DisplayName("F03.19 レイヤー設定 PATCH の upsert 原子性（並行 PATCH で 500 にしない）")
class CalendarLayerUpsertConcurrencyTest {

    private static final Long ME = 1001L;
    private static final Long MY_TEAM = 42L;

    /** {@code uk_user_calendar_layer} をキーに持つインメモリの実データ集合。 */
    private Map<String, UserCalendarLayerSettingEntity> store;
    private UserCalendarLayerSettingRepository repository;
    private AccessControlService accessControlService;
    private NameResolverService nameResolverService;
    private CalendarLayerService service;

    @BeforeEach
    void setUp() {
        store = new LinkedHashMap<>();
        repository = mock(UserCalendarLayerSettingRepository.class);
        accessControlService = mock(AccessControlService.class);
        nameResolverService = mock(NameResolverService.class);

        lenient().when(accessControlService.findAffiliatedScopeIds(ME, "TEAM"))
                .thenReturn(new LinkedHashSet<>(List.of(MY_TEAM)));
        lenient().when(nameResolverService.resolveScopeName(anyString(), anyLong()))
                .thenReturn("青葉FC");
        lenient().when(nameResolverService.resolveIconUrl(anyString(), anyLong()))
                .thenReturn(null);

        doAnswer(inv -> Optional.ofNullable(
                store.get(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))))
                .when(repository).findByUserIdAndScopeTypeAndScopeId(any(), anyString(), any());

        lenient().when(repository.countByUserId(any())).thenAnswer(inv -> (long) store.size());

        // INSERT IGNORE の意味論: 既にキーがあれば 0 件・例外なし。無ければ既定値の行を 1 件作る。
        doAnswer(inv -> {
            byte[] idBytes = inv.getArgument(0);
            Long userId = inv.getArgument(1);
            String scopeType = inv.getArgument(2);
            Long scopeId = inv.getArgument(3);
            String k = key(userId, scopeType, scopeId);
            if (store.containsKey(k)) {
                return 0;
            }
            UserCalendarLayerSettingEntity created = row(userId, scopeType, scopeId, null, false);
            created.setId(toUuid(idBytes));
            store.put(k, created);
            return 1;
        }).when(repository).insertIfAbsent(any(), any(), anyString(), any());

        // save の意味論: 未採番（新規）行が既存キーと衝突したら実 DB と同じく制約違反で落ちる。
        doAnswer(inv -> {
            UserCalendarLayerSettingEntity e = inv.getArgument(0);
            String k = key(e.getUserId(), e.getScopeType(), e.getScopeId());
            UserCalendarLayerSettingEntity current = store.get(k);
            if (e.getId() == null) {
                if (current != null) {
                    throw new DataIntegrityViolationException(
                            "Duplicate entry for key uk_user_calendar_layer");
                }
                e.setId(UUID.randomUUID());
            }
            store.put(k, e);
            return e;
        }).when(repository).save(any(UserCalendarLayerSettingEntity.class));

        service = new CalendarLayerService(repository, accessControlService, nameResolverService);
    }

    @Test
    @DisplayName("〔陽性〕find が空を返した直後に他リクエストが同じ行を作っても 500 にならず冪等に確定する")
    void concurrentPatch_whenRivalCreatesRowBetweenReadAndWrite_doesNotFail() {
        // 1 回目の読み取りが空を返した「直後」に、別 PATCH が同じ行を作る（TOCTOU の割り込み）。
        AtomicInteger reads = new AtomicInteger();
        doAnswer(inv -> {
            String k = key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            Optional<UserCalendarLayerSettingEntity> found = Optional.ofNullable(store.get(k));
            if (reads.getAndIncrement() == 0 && found.isEmpty()) {
                UserCalendarLayerSettingEntity rival = row(ME, "TEAM", MY_TEAM, "#059669", true);
                rival.setId(UUID.randomUUID());
                store.put(k, rival);
            }
            return found;
        }).when(repository).findByUserIdAndScopeTypeAndScopeId(any(), anyString(), any());

        CalendarLayerResponse[] result = new CalendarLayerResponse[1];
        assertThatCode(() -> result[0] = service.updateLayer(
                ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#dc2626", null)))
                .doesNotThrowAnyException();

        // 冪等: 行は 1 本だけ・自分の指定色が載り、送っていない hidden は相手の値を壊さない。
        assertThat(store).hasSize(1);
        assertThat(result[0].color()).isEqualTo("#DC2626");
        assertThat(store.get(key(ME, "TEAM", MY_TEAM)).getColor()).isEqualTo("#DC2626");
        assertThat(store.get(key(ME, "TEAM", MY_TEAM)).getHidden()).isTrue();
    }

    @Test
    @DisplayName("〔冪等〕同じ PATCH を 2 回送っても行は 1 本のまま・結果が同じ")
    void repeatedPatch_isIdempotent() {
        CalendarLayerResponse first = service.updateLayer(
                ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", true));
        CalendarLayerResponse second = service.updateLayer(
                ME, "TEAM", MY_TEAM, new CalendarLayerUpdateRequest("#DC2626", true));

        assertThat(store).hasSize(1);
        assertThat(second.color()).isEqualTo(first.color());
        assertThat(second.hidden()).isEqualTo(first.hidden());
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
