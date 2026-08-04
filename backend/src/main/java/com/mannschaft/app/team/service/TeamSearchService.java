package com.mannschaft.app.team.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.exception.OrganizationNotFoundException;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.dto.TeamSearchCriteria;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.metrics.TeamSearchMetrics;
import com.mannschaft.app.team.repository.TeamRepository;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F15.4 組織内チーム（店舗）検索のサービス層。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md §4.3} の権限判定フローを実装する。</p>
 *
 * <p>本サービスは未ログイン参照を許容するため {@code currentUserId} に {@code null} を受け取れる。
 * Controller 側でレスポンス DTO の射影（{@code TeamPublicSummaryResponse} / {@code TeamSearchResultResponse}）を行うため、
 * 本サービスは {@link Page}{@code <TeamEntity>} のまま返す。</p>
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TeamSearchService {

    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /** ソート許可カラムのホワイトリスト（設計書 §3.2）。 */
    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("nameKana", "name", "createdAt");

    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;
    private final TeamSearchMetrics teamSearchMetrics;

    /**
     * 自己プロキシ参照（issue #2544）。{@code @Cacheable} は Spring AOP プロキシ経由でのみ作用するため、
     * {@link #search} から {@link #searchTeamIdPage} を {@code this.} で呼ぶとキャッシュが発火しない。
     * 循環参照を避けるため {@code @Lazy} を付けたフィールド注入とする。
     */
    @Autowired
    @Lazy
    private TeamSearchService self;

    /**
     * 組織配下のチームを検索する。
     *
     * <p>呼び出しフロー（設計書 §4.3）:
     * <ol>
     *   <li>ソートカラムをホワイトリスト検証</li>
     *   <li>組織取得（{@code @SQLRestriction} により論理削除済みは自動除外）</li>
     *   <li>組織が PUBLIC でない場合は組織メンバーのみ続行、それ以外は 404 として隠蔽</li>
     *   <li>許可 {@code visibility} 集合を決定</li>
     *   <li>条件を合成して {@link TeamRepository#findAll(Specification, Pageable)} を実行</li>
     * </ol>
     * </p>
     *
     * @param orgId         組織 ID
     * @param criteria      検索条件（null 不可、各項目は任意）
     * @param currentUserId ログインユーザー ID（未ログインの場合 {@code null}）
     * @param pageable      ページング・ソート指定
     * @return 検索結果（権限スコープに合った可視性のチームのみ）
     * @throws OrganizationNotFoundException 組織が存在しない／論理削除済み／非 PUBLIC 組織で未ログイン・非メンバー
     * @throws IllegalArgumentException      sort カラムがホワイトリスト外
     *
     * <p><b>Phase 3 — Valkey キャッシュ（設計書 §6.5 / issue #2544 A 群で是正）:</b><br>
     * キャッシュに載せるのは {@link Page}{@code <TeamEntity>} ではなく
     * <b>チーム ID の並びと総件数だけ</b>（{@link TeamSearchIdPage}）である。
     * 旧実装は {@code Page<TeamEntity>} をそのまま {@code @Cacheable} していたが、
     * {@code PageImpl} は可視コンストラクタが複数あり {@code @JsonCreator} も既定コンストラクタも持たず、
     * {@code pageable} プロパティの静的型がインタフェースなので
     * {@code GenericJackson2JsonRedisSerializer} で<b>復元できない</b>。
     * さらに {@link TeamEntity} は setter を持たないため（素の {@code ObjectMapper} は
     * フィールド可視性 {@code PUBLIC_ONLY}）復元しても全フィールドが null の抜け殻になる。
     * 復元失敗は {@code LoggingCacheErrorHandler} の fail-open で WARN に握り潰されるため、
     * <b>put だけが毎回成功して get が毎回失敗する＝一度も効かないキャッシュ</b>のまま
     * Valkey に 60 秒ごとゴミを積み続けていた。
     * ID だけをキャッシュし、実体は主キー参照（{@code findAllById}）で引き直すことで
     * 高コストな動的検索クエリ（複数 JOIN・LIKE・件数カウント）を確実に節約する。
     * </p>
     *
     * <p><b>キャッシュキーと認可の位置:</b><br>
     * 認可（組織の存在確認・PRIVATE 組織の閲覧可否）は<b>キャッシュの外側</b>＝本メソッド側で行い、
     * キャッシュ対象メソッドには「解決済みの可視性スコープ」だけを渡す（issue #2496 の教訓）。
     * これによりキーは {@code currentUserId} ではなく {@code isMember}（可視性スコープ）で足り、
     * 未ログイン者と組織メンバーの 2 スコープで正しく共有される。
     * </p>
     *
     * <p><b>無効化方針:</b><br>
     * チーム更新（{@code TeamService.updateTeam} 等）からのキャッシュ無効化は
     * SCAN+DEL が必要となり本任務スコープ外。TTL 60 秒で最大 60 秒の
     * 反映遅延を許容する。Phase 4 以降で {@code @CacheEvict allEntries=true}
     * もしくは Redis SCAN 連携を検討する。
     * </p>
     *
     * <p><b>キャッシュヒット時のメトリクス:</b><br>
     * キャッシュ層が内側（{@link #searchTeamIdPage}）へ移ったことで、
     * {@link TeamSearchMetrics#recordSearch} はキャッシュヒット時も記録されるようになった
     * （検索リクエスト数の計測としてはむしろ正しい）。
     * </p>
     *
     * <p><b>0 件結果はキャッシュしない</b>（{@code unless} 条件）— 検索ボットによる
     * 無意味なキャッシュ占有を防ぐ。
     * </p>
     */
    public Page<TeamEntity> search(
            Long orgId,
            TeamSearchCriteria criteria,
            Long currentUserId,
            Pageable pageable
    ) {
        validateSort(pageable);

        // Phase 3 メトリクス計測開始（成功時のみ recordSearch を呼ぶ）
        Timer.Sample sample = teamSearchMetrics.startTimer();

        // 1. 組織の存在確認（@SQLRestriction("deleted_at IS NULL") により論理削除済みは自動除外）
        OrganizationEntity organization = organizationRepository.findById(orgId)
                .orElseThrow(OrganizationNotFoundException::new);

        // 2. PRIVATE 組織の閲覧権限確認（エニュメレーション対策で 404）
        boolean isMember = isOrganizationMember(orgId, currentUserId);
        if (organization.getVisibility() != OrganizationEntity.Visibility.PUBLIC && !isMember) {
            throw new OrganizationNotFoundException();
        }

        // 3. 許可 visibility 集合の決定
        // 組織メンバーは PUBLIC および GUESTS_AND_ABOVE / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE を閲覧可能。
        // 未ログイン・非メンバーは PUBLIC のみ。
        Set<TeamEntity.Visibility> allowedVisibilities = isMember
                ? EnumSet.of(TeamEntity.Visibility.PUBLIC, TeamEntity.Visibility.GUESTS_AND_ABOVE,
                        TeamEntity.Visibility.SUPPORTERS_AND_ABOVE, TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                : EnumSet.of(TeamEntity.Visibility.PUBLIC);

        // 4. 地域フィルタ（F22.1 dual-support）: code 指定があれば code を優先、無ければ名称にフォールバック。
        //    code/名称いずれの軸でも「都道府県が未指定なのに市区町村だけ指定」は city を無視する
        //    （prefecture コードまたは名称が無い限り、city 軸の絞り込みは無効化する）。
        boolean hasPrefecture = isPresent(criteria.prefectureCode()) || isPresent(criteria.prefecture());
        String effectiveCity = criteria.city();
        String effectiveCityCode = criteria.cityCode();
        if (!hasPrefecture && (isPresent(effectiveCity) || isPresent(effectiveCityCode))) {
            log.warn("F15.4 team search: prefecture が未指定のため city パラメータを無視します（orgId={}）", orgId);
            effectiveCity = null;
            effectiveCityCode = null;
        }

        // 5. Specification 合成（地域は dual-support フィルタで code 優先・名称フォールバック）
        Specification<TeamEntity> spec = Specification
                .where(TeamSearchSpecifications.notDeleted())
                .and(TeamSearchSpecifications.notArchived())
                .and(TeamSearchSpecifications.belongsToOrganization(orgId))
                .and(TeamSearchSpecifications.visibilityIn(allowedVisibilities))
                .and(TeamSearchSpecifications.nameOrKanaContains(criteria.keyword()))
                .and(TeamSearchSpecifications.prefectureFilter(criteria.prefectureCode(), criteria.prefecture()))
                .and(TeamSearchSpecifications.cityFilter(effectiveCityCode, effectiveCity))
                .and(TeamSearchSpecifications.templateEquals(criteria.template()));

        // 6. ID ページをキャッシュ経由で取得（issue #2544: self 経由でないと @Cacheable が発火しない）
        TeamSearchIdPage idPage = self.searchTeamIdPage(spec, isMember, pageable);

        // 7. 主キー参照で実体を引き直し、キャッシュされた ID の順序を復元する。
        List<TeamEntity> content = hydrate(idPage.teamIds());

        Page<TeamEntity> page = new PageImpl<>(content, pageable, idPage.totalElements());

        // 8. Phase 3 メトリクス記録（成功時のみ）
        String scope = isMember ? TeamSearchMetrics.SCOPE_MEMBER : TeamSearchMetrics.SCOPE_PUBLIC_ONLY;
        teamSearchMetrics.recordSearch(scope, currentUserId != null, sample, page.getNumberOfElements());

        return page;
    }

    /**
     * 検索結果の「チーム ID の並び」と「総件数」だけをキャッシュする（issue #2544 A 群）。
     *
     * <p><b>本メソッドは認可を一切行わない。</b> 認可は呼び出し元 {@link #search} が
     * キャッシュの外側で済ませ、その結果である可視性スコープ（{@code isMember}）を
     * 引数として受け取るだけである（キャッシュヒット時に認可がスキップされる
     * issue #2496 の「第三の型」を構造的に持ち込まないための配置）。</p>
     *
     * <p>戻り値の {@link TeamSearchIdPage} は record ＋ 可変 {@code ArrayList} であり、
     * 実シリアライザでの往復を {@code CacheValueSerializationRoundTripTest} が検証する。</p>
     *
     * <p><b>0 件結果はキャッシュしない</b>（{@code unless} 条件）— 検索ボットによる
     * 無意味なキャッシュ占有を防ぐ。</p>
     *
     * @param spec     合成済みの検索条件（可視性集合を含む。{@code hashCode} がキーの一部になる）
     * @param isMember 組織メンバーとして閲覧しているか（可視性スコープ。解決済み）
     * @param pageable ページング・ソート指定
     * @return チーム ID の並びと総件数
     */
    @Cacheable(
            value = "team-search",
            key = "T(java.util.Objects).hash(#spec, #isMember, #pageable)",
            unless = "#result == null || #result.totalElements() == 0"
    )
    public TeamSearchIdPage searchTeamIdPage(Specification<TeamEntity> spec,
                                             boolean isMember,
                                             Pageable pageable) {
        Page<TeamEntity> page = teamRepository.findAll(spec, pageable);
        // issue #2544 B 群: Stream#toList() が返す ImmutableCollections$ListN は
        // 既定コンストラクタを持たず Valkey から復元できないため、可変の ArrayList に集める。
        List<Long> ids = page.getContent().stream()
                .map(TeamEntity::getId)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return new TeamSearchIdPage(ids, page.getTotalElements());
    }

    /**
     * キャッシュされたチーム ID の並びを保ったまま実体を引き直す。
     *
     * <p>{@code findAllById} は引数の順序を保証しないため、ID → Entity のマップを経由して
     * 元の並び（ソート結果）を復元する。論理削除済みチームは {@code @SQLRestriction} により
     * 取得できないため、キャッシュ滞留中（最大 60 秒）に削除された ID は結果から自然に落ちる。</p>
     */
    private List<TeamEntity> hydrate(List<Long> ids) {
        if (ids.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, TeamEntity> byId = new LinkedHashMap<>();
        for (TeamEntity team : teamRepository.findAllById(ids)) {
            byId.put(team.getId(), team);
        }
        List<TeamEntity> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            TeamEntity team = byId.get(id);
            if (team != null) {
                ordered.add(team);
            }
        }
        return ordered;
    }

    /**
     * {@code team-search} キャッシュに載せる値（issue #2544 A 群）。
     *
     * <p>{@code Page}/{@code PageImpl} は Jackson で復元できないため、
     * キャッシュに載せるのは「ID の並び」と「総件数」だけにする。
     * record なので canonical constructor 経由で確実に往復でき、
     * {@code teamIds} は可変の {@code ArrayList} を渡すこと
     * （{@code List.of()} / {@code Stream#toList()} の不変実装は復元できない）。</p>
     *
     * @param teamIds       ページ内のチーム ID（ソート順）
     * @param totalElements 総件数
     */
    public record TeamSearchIdPage(List<Long> teamIds, long totalElements) {
    }

    /**
     * 当該ユーザーが組織のメンバーかどうかを返す。
     *
     * <p>{@code currentUserId} が {@code null} の場合は常に {@code false}（未ログインは非メンバー扱い）。</p>
     *
     * @param orgId         組織 ID
     * @param currentUserId ログインユーザー ID（未ログインの場合 {@code null}）
     * @return 組織メンバーであれば {@code true}
     */
    public boolean isOrganizationMember(Long orgId, Long currentUserId) {
        if (currentUserId == null) {
            return false;
        }
        return accessControlService.isMember(currentUserId, orgId, SCOPE_ORGANIZATION);
    }

    /** 非 null かつ非空白文字列なら {@code true}。 */
    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * ソートカラムがホワイトリストに含まれているかを検証する。
     *
     * @throws IllegalArgumentException 許可外のカラムが指定された場合
     */
    private void validateSort(Pageable pageable) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return;
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                throw new IllegalArgumentException(
                        "Invalid sort property: " + order.getProperty()
                                + " (allowed: " + ALLOWED_SORT_PROPERTIES + ")"
                );
            }
        }
    }
}
