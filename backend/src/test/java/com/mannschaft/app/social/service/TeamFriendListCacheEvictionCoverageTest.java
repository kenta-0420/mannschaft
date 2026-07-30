package com.mannschaft.app.social.service;

import com.mannschaft.app.social.dto.PastForwardHandling;
import com.mannschaft.app.team.dto.UpdateTeamRequest;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code teamFriendList} キャッシュの<b>失効網羅</b>を固定する番人テスト（issue #2496）。
 *
 * <h2>背景</h2>
 * <p>{@code teamFriendList} は導入以来一度も発火していなかったため、失効が壊れていても
 * 誰も気付かなかった。実際 {@code follow} / {@code unfollow} の {@code @CacheEvict} は
 * {@code key = "#teamId"} 単体で、{@code @Cacheable} 側の
 * {@code "teamId:userId:page:size:publicOnly"} 形式と噛み合っておらず
 * <b>一件も失効しない</b>状態だった。キャッシュを実際に効かせる以上、
 * 「古い表示が残り続ける」不具合を防ぐため失効の網羅を機械的に固定する。</p>
 *
 * <h2>固定する不変条件</h2>
 * <ol>
 *   <li>キャッシュ内容を変化させ得る操作すべてに {@code teamFriendList} の
 *       {@code @CacheEvict} が宣言されていること</li>
 *   <li>その {@code @CacheEvict} が {@code allEntries = true} であること。
 *       キーは閲覧者・ページ・{@code publicOnly} の直積であり、個別キーの列挙は不可能なので
 *       キー指定の evict は必ず取りこぼす</li>
 * </ol>
 *
 * <h2>失効すべき操作の全数</h2>
 * <table border="1">
 *   <caption>キャッシュ内容への影響</caption>
 *   <tr><th>操作</th><th>キャッシュ内容への影響</th></tr>
 *   <tr><td>{@code TeamFriendsService#follow}</td><td>相互フォロー成立でフレンドが増える</td></tr>
 *   <tr><td>{@code TeamFriendsService#unfollow}</td><td>フレンド関係が解消され減る</td></tr>
 *   <tr><td>{@code TeamFriendVisibilityService#setVisibility}</td>
 *       <td>{@code isPublic} が変わり SUPPORTER 向け一覧の内容が変わる</td></tr>
 *   <tr><td>{@code TeamService#updateTeam}</td>
 *       <td>チーム名が変わる＝キャッシュ済み {@code friendTeamName} が stale 化する</td></tr>
 *   <tr><td>{@code TeamService#deleteTeam}</td><td>削除済みチームがキャッシュに残らないようにする</td></tr>
 * </table>
 */
@DisplayName("teamFriendList キャッシュ 失効網羅 番人 (issue #2496)")
class TeamFriendListCacheEvictionCoverageTest {

    private static final String CACHE_NAME = "teamFriendList";

    /**
     * 対象メソッドに宣言された {@code teamFriendList} 向け {@link CacheEvict} を集める
     * （{@link Caching} でまとめられている場合も展開する）。
     */
    private static List<CacheEvict> teamFriendListEvictsOf(Class<?> type, String methodName,
                                                           Class<?>... parameterTypes) {
        Method method;
        try {
            method = type.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "対象メソッドが見つからない（シグネチャ変更時は本テストも追随すること）: "
                            + type.getSimpleName() + "#" + methodName, e);
        }

        List<CacheEvict> all = new ArrayList<>();
        CacheEvict single = method.getAnnotation(CacheEvict.class);
        if (single != null) {
            all.add(single);
        }
        Caching caching = method.getAnnotation(Caching.class);
        if (caching != null) {
            all.addAll(Arrays.asList(caching.evict()));
        }

        return all.stream()
                .filter(evict -> Arrays.asList(evict.value()).contains(CACHE_NAME)
                        || Arrays.asList(evict.cacheNames()).contains(CACHE_NAME))
                .toList();
    }

    private static void assertEvictsAllEntries(Class<?> type, String methodName,
                                               Class<?>... parameterTypes) {
        List<CacheEvict> evicts = teamFriendListEvictsOf(type, methodName, parameterTypes);

        assertThat(evicts)
                .as("%s#%s は teamFriendList を失効させなければならない"
                        + "（この操作でフレンド一覧の内容が変わるため）", type.getSimpleName(), methodName)
                .isNotEmpty();

        assertThat(evicts)
                .as("%s#%s の teamFriendList evict は allEntries=true でなければならない。"
                        + "@Cacheable のキーは 'teamId:userId:page:size:publicOnly' の直積であり、"
                        + "キー指定の evict では必ず取りこぼす", type.getSimpleName(), methodName)
                .allMatch(CacheEvict::allEntries);

        assertThat(evicts)
                .as("%s#%s の teamFriendList evict にキー式を書いてはならない"
                        + "（@Cacheable 側のキー形式と噛み合わず失効しない）", type.getSimpleName(), methodName)
                .allMatch(evict -> evict.key().isEmpty());
    }

    @Test
    @DisplayName("follow（フレンド成立）が teamFriendList を allEntries で失効させる")
    void follow_が失効させる() {
        assertEvictsAllEntries(TeamFriendsService.class, "follow", Long.class, Long.class, Long.class);
    }

    @Test
    @DisplayName("unfollow（フレンド解消）が teamFriendList を allEntries で失効させる")
    void unfollow_が失効させる() {
        assertEvictsAllEntries(TeamFriendsService.class, "unfollow",
                Long.class, Long.class, PastForwardHandling.class, Long.class);
    }

    @Test
    @DisplayName("setVisibility（公開設定変更）が teamFriendList を allEntries で失効させる")
    void setVisibility_が失効させる() {
        assertEvictsAllEntries(TeamFriendVisibilityService.class, "setVisibility",
                Long.class, Long.class, boolean.class, Long.class);
    }

    @Test
    @DisplayName("updateTeam（チーム名変更）が teamFriendList を失効させる（friendTeamName の stale 防止）")
    void updateTeam_が失効させる() {
        assertEvictsAllEntries(TeamService.class, "updateTeam", Long.class, UpdateTeamRequest.class);
    }

    @Test
    @DisplayName("deleteTeam（チーム削除）が teamFriendList を失効させる")
    void deleteTeam_が失効させる() {
        assertEvictsAllEntries(TeamService.class, "deleteTeam", Long.class, Long.class);
    }

    @Test
    @DisplayName("restoreTeam（論理削除の復元）が teamFriendList を失効させる（friendTeamName=null 残留の防止）")
    void restoreTeam_が失効させる() {
        // deleteTeam で全消し → TTL(30分) の間に誰かが一覧を引くと
        // toView の teamRepository.findById(...).orElse(null)（@SQLRestriction で削除済みは引けない）により
        // friendTeamName = null がキャッシュされる。restoreTeam が失効させないと、
        // 復元後もフレンド名が空欄のまま最大 30 分表示され続ける。
        assertEvictsAllEntries(TeamService.class, "restoreTeam", Long.class);
    }

    @Test
    @DisplayName("アーカイブ/復元(archive/unarchive)は対象外であることの根拠を明示する")
    void アーカイブは対象外である() {
        // archiveTeam / unarchiveTeam は archivedAt しか触らず、TeamEntity の @SQLRestriction は
        // deleted_at IS NULL のみを見る。よって teamRepository.findById(...) の可否は変わらず、
        // キャッシュ値（teamFriendId / friendTeamId / friendTeamName / isPublic / establishedAt）の
        // どの項目にも影響しない。＝ teamFriendList の失効は不要。
        // 一方 restoreTeam は deleted_at を戻すため @SQLRestriction の効き方が反転する（上のテスト）。
        assertThat(teamFriendListEvictsOf(TeamService.class, "archiveTeam", Long.class))
                .as("archiveTeam はキャッシュ値のどの項目にも影響しないため evict 不要"
                        + "（不要な全消しはヒット率を下げるだけ）")
                .isEmpty();
    }
}
