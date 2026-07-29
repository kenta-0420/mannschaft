package com.mannschaft.app.social.dto;

import com.mannschaft.app.config.RedisConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TeamFriendView} のキャッシュ往復シリアライズ回帰テスト（issue #2496）。
 *
 * <h2>なぜ「本番と同一の」シリアライザで回すのか</h2>
 * <p>本 PR の存在理由は「シリアライズ形状の事故が fail-open に隠れて
 * 『一度も効かないキャッシュ』になっていた」ことである。したがって素の
 * {@code ObjectMapper} で往復させても意味がない —— それでは本 PR が根治しようとしている
 * 事故と同じ形状を再び見逃す。</p>
 *
 * <p>本テストは {@link RedisConfig#redisCacheConfiguration()} が組み立てた
 * <b>実物の {@code SerializationPair}</b>（= {@code GenericJackson2JsonRedisSerializer} ＋
 * {@code activateDefaultTyping(..., EVERYTHING)} ＋ {@code JavaTimeModule} ＋
 * {@code ParameterNamesModule} ＋ {@code BasicPolymorphicTypeValidator}）を
 * そのまま取り出して往復させる。構成をテスト側に書き写さないため、
 * 本番構成を変更したら本テストが自動的に追随する（設定ドリフトが起きない）。</p>
 *
 * <p>実 Redis には接続しない（シリアライザ単体で {@code write}/{@code read} するだけ）。</p>
 *
 * <h2>固定する不変条件</h2>
 * <ol>
 *   <li>{@code List<TeamFriendView>}（キャッシュに載る実際の型）が往復で復元できること</li>
 *   <li>{@code isPublic} が往復で化けないこと（ゲッター名 {@code isPublic()} は
 *       {@code "public"} と書き出されるのに対し、ビルダー引数名は {@code isPublic} という
 *       非対称があり、放置すると常に {@code false} に化ける）</li>
 *   <li>コレクション実装が復元可能な型であること（{@code Stream#toList()} が返す
 *       {@code ImmutableCollections$ListN} は既定コンストラクタが無く、
 *       {@code DefaultTyping.EVERYTHING} で埋め込まれた型 ID から復元できない）</li>
 * </ol>
 */
@DisplayName("TeamFriendView キャッシュ往復シリアライズ回帰テスト (issue #2496)")
class TeamFriendViewCacheSerializationTest {

    /** 本番の {@code teamFriendList} キャッシュが実際に使う値シリアライザ。 */
    private final SerializationPair<Object> valueSerializer =
            new RedisConfig().redisCacheConfiguration().getValueSerializationPair();

    private static TeamFriendView sample(boolean isPublic) {
        return TeamFriendView.builder()
                .teamFriendId(1L)
                .friendTeamId(20L)
                .friendTeamName("フレンドチーム")
                .isPublic(isPublic)
                .establishedAt(LocalDateTime.of(2026, 7, 29, 12, 34, 56))
                .build();
    }

    /** 本番と同じ経路（Valkey への書き込み → 読み出し）で往復させる。 */
    private Object roundTrip(Object value) {
        ByteBuffer written = valueSerializer.write(value);
        return valueSerializer.read(written);
    }

    @SuppressWarnings("unchecked")
    private List<TeamFriendView> roundTripList(List<TeamFriendView> value) {
        return (List<TeamFriendView>) roundTrip(value);
    }

    /** {@code listFriendViews} が返すのと同じ形（可変 ArrayList）を作る。 */
    private static List<TeamFriendView> cachedShape(TeamFriendView... views) {
        return new ArrayList<>(List.of(views));
    }

    @Test
    @DisplayName("キャッシュに載る List<TeamFriendView> が実シリアライザで往復復元できる")
    void リストが実シリアライザで往復できる() {
        List<TeamFriendView> original = cachedShape(sample(true), sample(false));

        List<TeamFriendView> restored = roundTripList(original);

        assertThat(restored)
                .as("Valkey 往復で復元できないと、キャッシュヒットのたびに fail-open で握り潰され"
                        + "『毎回ミスするだけの効かないキャッシュ』に静かに戻る")
                .hasSize(2);
        assertThat(restored.get(0).getFriendTeamId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("isPublic=true が実シリアライザの往復で保存される（false に化けない）")
    void isPublicTrueが往復で保存される() {
        List<TeamFriendView> restored = roundTripList(cachedShape(sample(true)));

        assertThat(restored.get(0).isPublic())
                .as("往復で isPublic が false に化けると、公開フレンドが非公開表示になる"
                        + "（逆向きに化ければ非公開フレンドが公開表示される）")
                .isTrue();
    }

    @Test
    @DisplayName("isPublic=false も実シリアライザの往復で保存される")
    void isPublicFalseが往復で保存される() {
        List<TeamFriendView> restored = roundTripList(cachedShape(sample(false)));

        assertThat(restored.get(0).isPublic()).isFalse();
    }

    @Test
    @DisplayName("全フィールドが実シリアライザの往復で保存される")
    void 全フィールドが往復で保存される() {
        TeamFriendView original = sample(true);

        TeamFriendView restored = roundTripList(cachedShape(original)).get(0);

        assertThat(restored.getTeamFriendId()).isEqualTo(original.getTeamFriendId());
        assertThat(restored.getFriendTeamId()).isEqualTo(original.getFriendTeamId());
        assertThat(restored.getFriendTeamName()).isEqualTo(original.getFriendTeamName());
        assertThat(restored.isPublic()).isEqualTo(original.isPublic());
        assertThat(restored.getEstablishedAt())
                .as("LocalDateTime は JavaTimeModule 経由で往復するため秒まで一致するべき")
                .isEqualTo(original.getEstablishedAt());
    }

    @Test
    @DisplayName("空リストも往復できる（フレンド0件のチーム）")
    void 空リストも往復できる() {
        assertThat(roundTripList(cachedShape())).isEmpty();
    }

    @Test
    @DisplayName("シリアライズ結果に型情報が埋め込まれている（EVERYTHING 前提の明示）")
    void 型情報が埋め込まれている() {
        String json = new String(
                toBytes(valueSerializer.write(cachedShape(sample(true)))), StandardCharsets.UTF_8);

        assertThat(json)
                .as("DefaultTyping.EVERYTHING により具象型名が型 ID として埋め込まれる。"
                        + "これがコレクション実装の差で復元可否が変わる理由である")
                .contains("java.util.ArrayList")
                .contains(TeamFriendView.class.getName());
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private static byte[] toBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
