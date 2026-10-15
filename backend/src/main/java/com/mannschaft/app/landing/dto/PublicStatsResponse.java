package com.mannschaft.app.landing.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * ランディングページ公開統計レスポンス。
 *
 * <h2>なぜ明示的な {@link JsonCreator} コンストラクタを持つのか（issue #2544 C 群）</h2>
 * <p>
 * 本 DTO は {@code public-stats} キャッシュ（TTL 5 分）に載る＝Valkey へ JSON シリアライズされ、
 * ヒット時に JSON からデシリアライズされる。旧実装は
 * {@code @Getter @NoArgsConstructor @AllArgsConstructor @Builder} で、
 * <b>setter も {@link JsonCreator} も持たなかった</b>。Jackson は複数のコンストラクタが
 * 候補になるとき既定コンストラクタを選び、以後は setter かフィールド書き込みで値を入れるが、
 * {@code RedisConfig} が使う {@code ObjectMapper} は素の {@code new ObjectMapper()} であり
 * フィールド可視性は {@code PUBLIC_ONLY}——private フィールドへ書き込む経路が無い。
 * 結果として往復後は {@code totalUsers / totalTeams / totalOrganizations} が
 * <b>すべて {@code 0} に化ける</b>（ランディングの統計が 5 分間ゼロ表示）。
 * しかも例外にならないため {@code LoggingCacheErrorHandler} の fail-open ログにも残らない。
 * </p>
 * <p>
 * そこで全項目コンストラクタを {@link JsonCreator} として明示し、
 * ビルダーの生成元と Jackson のデシリアライズ契約を一本化する。
 * JSON のプロパティ名は従来の getter 由来の名前と同一であり、API 出力・OpenAPI スキーマは不変。
 * </p>
 */
@Getter
public class PublicStatsResponse {

    private final long totalUsers;
    private final long totalTeams;
    private final long totalOrganizations;
    private final Map<String, CountryStats> countryBreakdown;

    /**
     * 全項目コンストラクタ。{@code @Builder} をここに付けることで、
     * Jackson のデシリアライズ契約（{@link JsonCreator}）とビルダーの生成元を一本化する。
     */
    @Builder
    @JsonCreator
    public PublicStatsResponse(
            @JsonProperty("totalUsers") long totalUsers,
            @JsonProperty("totalTeams") long totalTeams,
            @JsonProperty("totalOrganizations") long totalOrganizations,
            @JsonProperty("countryBreakdown") Map<String, CountryStats> countryBreakdown) {
        this.totalUsers = totalUsers;
        this.totalTeams = totalTeams;
        this.totalOrganizations = totalOrganizations;
        this.countryBreakdown = countryBreakdown;
    }

    /** 国別内訳。親と同じ理由で {@link JsonCreator} を明示する。 */
    @Getter
    public static class CountryStats {

        private final long users;
        private final long teams;
        private final long organizations;

        @Builder
        @JsonCreator
        public CountryStats(
                @JsonProperty("users") long users,
                @JsonProperty("teams") long teams,
                @JsonProperty("organizations") long organizations) {
            this.users = users;
            this.teams = teams;
            this.organizations = organizations;
        }
    }
}
