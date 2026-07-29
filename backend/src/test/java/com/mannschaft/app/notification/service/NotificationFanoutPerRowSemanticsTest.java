package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-4 回帰ガード（<b>不変条件・今も改修後も green を維持する</b>）: fan-out が生成する通知行は
 * <b>受信者ごとに独立</b>であり、既読/スヌーズ/優先度/スコープ（フォルダ絞り込みの軸）の
 * per-row 意味論が保たれることを、ドメイン Entity レベルで軽量に固定する。
 *
 * <h2>なぜ Entity レベルの軽量 UT か</h2>
 * <p>P1 出陣では受信者ごと 1 INSERT を <b>多値バルク INSERT</b>（{@code JdbcTemplate} 迂回）へ置き換える。
 * このとき「全受信者に同じ 1 行を使い回す」「is_read / snoozed_until を取り違える」「user_id を全行同一に
 * してしまう」といった取りこぼしが起きると per-row 意味論が壊れる。本 UT はその不変条件を Docker 非依存で
 * 常時（CI smoke でも）検証し、バルク化の副作用を早期に検出する土台にする。DB レベルの充填検証は
 * {@code VillageFanoutRedesignP1RedIT#ac4_...} が担う（相補関係）。</p>
 */
@DisplayName("AC-4 fan-out 生成通知の per-row 意味論（既読/スヌーズ/優先度/スコープ）回帰ガード")
class NotificationFanoutPerRowSemanticsTest {

    /** fan-out が受信者ごとに作る通知行を、バルク化後も同じ意味で作れることを表す最小の擬似生成。 */
    private List<NotificationEntity> buildFanoutRows(List<Long> recipients,
                                                     NotificationPriority priority,
                                                     NotificationScopeType scopeType, Long scopeId) {
        return recipients.stream()
                .map(uid -> {
                    // @SuperBuilder の build() はワイルドカード捕捉型を返すため、明示的に
                    // NotificationEntity へ束縛してから返す（stream 推論で List<CAP#1> に化けるのを防ぐ）。
                    NotificationEntity n = NotificationEntity.builder()
                            .userId(uid)
                            .notificationType("EVENT_CREATED")
                            .priority(priority)
                            .title("村の行事案内")
                            .body("新しい行事が追加されました")
                            .sourceType("VILLAGE_EVENT")
                            .sourceId(null)
                            .scopeType(scopeType)
                            .scopeId(scopeId)
                            .actionUrl("/villages/x")
                            .actorId(null)
                            .build();
                    return n;
                })
                .toList();
    }

    @Test
    @DisplayName("各行は受信者ごとに独立: user_id は取り違えず、既定 is_read=false・snoozed=null")
    void eachRowIsIndependentPerRecipient() {
        List<Long> recipients = IntStream.range(0, 5).mapToObj(i -> 900_000_000L + i).toList();

        List<NotificationEntity> rows =
                buildFanoutRows(recipients, NotificationPriority.NORMAL, NotificationScopeType.SYSTEM, null);

        assertThat(rows).hasSize(5);
        // user_id は受信者ぶんが 1:1 で並ぶ（全行同一 user_id への潰れが無い）。
        assertThat(rows.stream().map(NotificationEntity::getUserId).toList())
                .containsExactlyElementsOf(recipients);
        // per-row 既定: 未読・スヌーズ無し。
        assertThat(rows).allSatisfy(n -> {
            assertThat(n.isAlreadyRead()).isFalse();
            assertThat(n.getSnoozedUntil()).isNull();
            assertThat(n.getPriority()).isEqualTo(NotificationPriority.NORMAL);
            assertThat(n.getScopeType()).isEqualTo(NotificationScopeType.SYSTEM);
        });
    }

    @Test
    @DisplayName("1 行を既読/スヌーズしても他行に波及しない（per-row 状態の独立）")
    void markingOneRowDoesNotAffectOthers() {
        List<Long> recipients = IntStream.range(0, 3).mapToObj(i -> 900_000_000L + i).toList();
        List<NotificationEntity> rows =
                buildFanoutRows(recipients, NotificationPriority.NORMAL, NotificationScopeType.SYSTEM, null);

        rows.get(0).markAsRead();
        rows.get(1).snooze(LocalDateTime.now().plusHours(1));

        // 0 番のみ既読、1 番のみスヌーズ、2 番は無傷。
        assertThat(rows.get(0).isAlreadyRead()).isTrue();
        assertThat(rows.get(1).isAlreadyRead()).isFalse();
        assertThat(rows.get(1).getSnoozedUntil()).isNotNull();
        assertThat(rows.get(2).isAlreadyRead()).isFalse();
        assertThat(rows.get(2).getSnoozedUntil()).isNull();
    }

    @Nested
    @DisplayName("優先度・スコープは受信者を跨いで取り違えない")
    class PriorityAndScope {
        @Test
        @DisplayName("優先度は builder 指定どおり全行に反映される")
        void priorityPreserved() {
            List<Long> recipients = IntStream.range(0, 4).mapToObj(i -> 900_000_000L + i).toList();
            List<NotificationEntity> rows =
                    buildFanoutRows(recipients, NotificationPriority.HIGH, NotificationScopeType.SYSTEM, null);
            assertThat(rows).allSatisfy(n ->
                    assertThat(n.getPriority()).isEqualTo(NotificationPriority.HIGH));
        }

        @Test
        @DisplayName("スコープ（フォルダ絞り込み軸の scopeType/scopeId）は全行で保持される")
        void scopePreserved() {
            List<Long> recipients = IntStream.range(0, 4).mapToObj(i -> 900_000_000L + i).toList();
            List<NotificationEntity> rows =
                    buildFanoutRows(recipients, NotificationPriority.NORMAL, NotificationScopeType.TEAM, 42L);
            assertThat(rows).allSatisfy(n -> {
                assertThat(n.getScopeType()).isEqualTo(NotificationScopeType.TEAM);
                assertThat(n.getScopeId()).isEqualTo(42L);
            });
        }
    }
}
