package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * お知らせ既読管理サービス（F02.6）。
 *
 * <p>
 * お知らせウィジェットの既読マーク（単件・全件）を担う。
 * 既読状態のバッチ取得ヘルパー（N+1 防止）も提供する。
 * </p>
 *
 * <p>
 * <b>認可モデル（認可根治「裏目付」第二陣 C-social・マスター御裁可 2026-07-28）</b>:
 * 既読系 EP の規則は<b>「自分に見えているお知らせなら既読にしてよい」</b>である。
 * すなわち次の 2 段で判定する。
 * </p>
 * <ol>
 *   <li><b>帰属検証</b> — 対象お知らせが URL のパス変数で指定されたスコープに属すること。
 *       これが無いと他テナントの ID を自スコープの URL に差し込めてしまう。</li>
 *   <li><b>可視性検証</b> — その閲覧者にそのお知らせが<b>一覧で見えるか</b>。
 *       判定は一覧側（{@link AnnouncementFeedService#getAnnouncementFeed} /
 *       {@link AnnouncementFeedQueryRepository#findByScope}）と<b>同一の正準経路</b>
 *       （{@link RoleResolver#resolveViewerRole} → {@link AnnouncementVisibility#allowedFor}）
 *       を流用する。独自の可視性述語は書かない（漏洩源になるため）。</li>
 * </ol>
 *
 * <p>
 * <b>なぜ「在籍」ではなく「可視性」か</b>: 一覧は非メンバーにも {@code PUBLIC} のお知らせを
 * 返す仕様であるため、既読を「メンバー以上」に固定すると<b>見えているものが既読にできない</b>
 * という不整合（画面上は「クリックしても何も起きない」）が生じる。逆に「在籍」だけを見ると、
 * 応援者が一覧に出ない内輪限定（{@code MEMBERS_AND_ABOVE}）のお知らせを既読化でき、
 * 応答差分から内輪お知らせ ID の実在を判別できるうえ、後日 MEMBER に昇格した際に
 * 既読済み扱いで未読バッジに出ず通知の見落としになる。
 * 「見える＝既読にできる」に揃えることで両方が同時に解消する。
 * </p>
 *
 * <p>
 * <b>存在秘匿</b>: 「当該スコープに属さない」「そもそも存在しない」「自分には可視でない」の
 * 3 つはいずれも {@link AnnouncementErrorCode#ANNOUNCE_001} に畳み込み、応答差分から
 * ID の実在・可視性が漏れないようにする。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnnouncementReadService {

    /**
     * 一括既読のチャンクサイズ（#2494）。
     *
     * <p>1 回のクエリ / {@code INSERT} バッチで扱う最大件数。
     * これにより SQL のプレースホルダ数・{@code max_allowed_packet} が
     * スコープの feed 総数に依らず定数上限に収まる。</p>
     */
    static final int MARK_ALL_BATCH_SIZE = 500;

    /**
     * 一括既読 1 リクエストあたりの最大チャンク数（#2494 の防御上限）。
     *
     * <p>未読は 1 チャンク処理するごとに必ず減るのでループは自然終了する。
     * 本定数は「万一減らない事態」で無限ループにならないための防御であり、
     * 同時に 1 リクエストの最悪実行時間を {@code MARK_ALL_BATCH_SIZE * MAX_BATCHES} 件
     * （= 10,000 件）に固定する資源上限も兼ねる。上限に達した場合は WARN ログを残す
     * （握りつぶさない）。残余は次回の一括既読で処理される。</p>
     */
    static final int MARK_ALL_MAX_BATCHES = 20;

    private final AnnouncementFeedRepository feedRepository;
    private final AnnouncementFeedQueryRepository feedQueryRepository;
    private final AnnouncementReadStatusRepository readStatusRepository;
    private final ProxyInputContext proxyInputContext;
    private final AnnouncementCreationService creationService;

    /**
     * 閲覧者ロール解決（一覧側 Controller と同一の正準経路）。
     *
     * <p>{@code dashboard} ドメインの Service を参照するが、これは
     * {@code AnnouncementFeedController} / {@code AnnouncementFeedOrgController} /
     * {@code AnnouncementInboxAdapter} が既に採っている経路と同一である
     * （ドメイン間のデータ取得は Service メソッド呼び出し経由・CLAUDE.md ドメイン境界の原則）。</p>
     */
    private final RoleResolver roleResolver;

    // ═════════════════════════════════════════════════════════════
    // 2.5 既読マーク（単件）
    // ═════════════════════════════════════════════════════════════

    /**
     * お知らせを既読にする（冪等）。
     *
     * <p>
     * 既に既読レコードが存在する場合は何もしない。
     * </p>
     *
     * <p>
     * <b>認可</b>: 対象お知らせが当該スコープに帰属し、かつ<b>その閲覧者に可視である</b>こと
     * （{@link #assertReadable}）を検証する。スコープは URL のパス変数
     * （{@code teamId} / {@code orgId}）から Controller が渡す。
     * </p>
     *
     * @param scopeType      スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId        スコープ ID（teams.id または organizations.id）
     * @param announcementId お知らせフィード ID
     * @param userId         ユーザー ID
     */
    @Transactional
    public void markAsRead(AnnouncementScopeType scopeType, Long scopeId, Long announcementId, Long userId) {
        // 認可: 帰属検証 + 可視性検証。
        // 「属さない」「存在しない」「可視でない」はいずれも ANNOUNCE_001 に畳み込む（存在秘匿）。
        assertReadable(scopeType, scopeId, announcementId, userId);

        // 冪等: 既読済みなら何もしない
        boolean alreadyRead = readStatusRepository
                .findByAnnouncementFeedIdAndUserId(announcementId, userId)
                .isPresent();
        if (alreadyRead) {
            return;
        }

        AnnouncementReadStatusEntity status = AnnouncementReadStatusEntity.builder()
                .announcementFeedId(announcementId)
                .userId(userId)
                .build();
        status = readStatusRepository.save(status);

        // 代理確認の場合: proxy_input_records を作成し、is_proxy_confirmed フラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = creationService.buildAndSaveAnnouncementProxyRecord(
                    "ANNOUNCEMENT_READ", announcementId);
            readStatusRepository.save(status.toBuilder()
                    .isProxyConfirmed(true)
                    .proxyInputRecordId(proxyRecord.getId())
                    .build());
        }

        log.debug("既読マーク完了 announcementId={}, userId={}", announcementId, userId);
    }

    // ═════════════════════════════════════════════════════════════
    // 2.6 全件既読
    // ═════════════════════════════════════════════════════════════

    /**
     * スコープ内の全お知らせを既読にする。
     *
     * <p>
     * 既読済みのものは除外し、未読のものだけを既読登録する。
     * </p>
     *
     * <p>
     * <b>認可</b>: スコープ内のフィードのうち<b>その閲覧者に可視なものだけ</b>を既読化する。
     * 可視性の判定は一覧クエリと<b>同一の WHERE 句</b>
     * （{@link AnnouncementFeedQueryRepository#findUnreadIdsByScope} が
     * {@code findByScope} と共有する定数）で DB 側に寄せてある。非メンバーが他テナントの
     * スコープを指定しても、可視なもの（{@code PUBLIC}）以外には既読行が 1 件も作られない。
     * 応援者に対して内輪限定（{@code MEMBERS_AND_ABOVE}）が既読化されることもない。
     * </p>
     *
     * <p>
     * <b>件数上限（#2494）</b>: 旧実装はスコープ内の feed を<b>limit 無しで全件</b>取り、
     * Java 側で可視性を絞ったうえで既読済み ID の引き当てに全件を {@code IN} 句へ渡していた。
     * 長く運用されたスコープほどプレースホルダ数と {@code INSERT} 件数が伸び、
     * {@code max_allowed_packet} やプリペアドステートメントのパラメータ上限に触れうるうえ、
     * 1 リクエストの実行時間が<b>スコープの歴史の長さ</b>に比例していた。
     * 現行は「未読分だけを DB 側で絞る」クエリを {@link #MARK_ALL_BATCH_SIZE} 件ずつ
     * 繰り返す方式に変え、
     * </p>
     * <ul>
     *   <li>feed ID の {@code IN} 句が消えた（バインドは可視性集合の最大 3 個のみ）</li>
     *   <li>1 クエリ / 1 {@code INSERT} バッチの件数が定数上限に収まる</li>
     *   <li><b>クエリ回数・{@code INSERT} 件数がスコープの feed 総数に依らず、未読件数だけで決まる</b>
     *       （{@code ceil(未読件数 / MARK_ALL_BATCH_SIZE)} 回。既読済みが何万件あっても
     *       クエリ 1 回・{@code INSERT} 0 件で終わる）</li>
     * </ul>
     *
     * <p><b>正確を期すための注記（残課題・#2494 検分）</b>: 各周回は
     * {@code ORDER BY a.id ASC} を毎回<b>先頭から</b>引き直すため、k 周目は直前までに
     * 既読化した {@code k × MARK_ALL_BATCH_SIZE} 行を {@code NOT EXISTS} で潰しながら進む。
     * よって<b>総インデックスプローブ数は未読件数に対して二次的</b>である
     * （最悪の 20 周では約 10 万回）。「未読件数にのみ比例する」のは<b>クエリ回数</b>であって
     * 総プローブ数ではない。{@code AND a.id > :lastSeenId} のカーソルを足せば線形にできるが、
     * 現実の未読件数（数百〜数千）では 1〜数周で終わるため #2494 では見送った。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @param userId    ユーザー ID
     * @return 新たに既読化した件数（既読済みだったものは含まない）
     */
    @Transactional
    public int markAllAsRead(AnnouncementScopeType scopeType, Long scopeId, Long userId) {
        // 認可: 閲覧者が当該スコープで見られる visibility 集合（一覧側と同一の正準経路）。
        // fail-closed のスコープ種別では空集合になり、以降で 1 件も既読化されない。
        Set<String> allowedVisibilities = resolveAllowedVisibilities(scopeType, scopeId, userId);
        if (allowedVisibilities.isEmpty()) {
            return 0;
        }

        int markedCount = 0;
        for (int batch = 0; batch < MARK_ALL_MAX_BATCHES; batch++) {
            // 「可視かつ未読」を DB 側で絞る。可視性の WHERE 句は一覧クエリと同一の定数を共有する。
            List<Long> unreadFeedIds = feedQueryRepository.findUnreadIdsByScope(
                    scopeType, scopeId, allowedVisibilities, userId, MARK_ALL_BATCH_SIZE);
            if (unreadFeedIds.isEmpty()) {
                return logAndReturn(scopeType, scopeId, userId, markedCount);
            }

            List<AnnouncementReadStatusEntity> newReadStatuses = unreadFeedIds.stream()
                    .<AnnouncementReadStatusEntity>map(feedId -> AnnouncementReadStatusEntity.builder()
                            .announcementFeedId(feedId)
                            .userId(userId)
                            .build())
                    .toList();
            readStatusRepository.saveAll(newReadStatuses);
            // 次バッチの NOT EXISTS が今回の INSERT を確実に見えるようにフラッシュする
            // （自動フラッシュ任せにせず、チャンク境界を明示的に確定させる）。
            readStatusRepository.flush();
            markedCount += unreadFeedIds.size();

            // 取得件数がチャンク未満なら未読は尽きている（余計な空クエリを 1 回省く）。
            if (unreadFeedIds.size() < MARK_ALL_BATCH_SIZE) {
                return logAndReturn(scopeType, scopeId, userId, markedCount);
            }
        }

        // 防御上限に到達。症状を隠さず WARN で記録する（残余は次回の一括既読で処理される）。
        log.warn("全件既読マークが1リクエストの上限に到達 scopeType={}, scopeId={}, userId={}, marked={} "
                        + "（未読が残っている可能性あり・上限={}件）",
                scopeType, scopeId, userId, markedCount, MARK_ALL_BATCH_SIZE * MARK_ALL_MAX_BATCHES);
        return markedCount;
    }

    private int logAndReturn(AnnouncementScopeType scopeType, Long scopeId, Long userId, int markedCount) {
        log.debug("全件既読マーク完了 scopeType={}, scopeId={}, userId={}, count={}",
                scopeType, scopeId, userId, markedCount);
        return markedCount;
    }

    // ═════════════════════════════════════════════════════════════
    // 認可ヘルパー（認可根治「裏目付」第二陣 C-social）
    // ═════════════════════════════════════════════════════════════

    /**
     * 対象お知らせが当該スコープに帰属し、かつ閲覧者に可視であることを検証する。
     * 違反時は {@link AnnouncementErrorCode#ANNOUNCE_001}。
     *
     * <p><b>存在秘匿</b>: 「そもそも存在しない」「当該スコープに属さない（越境）」
     * 「自分には可視でない（内輪限定・削除済み・期限切れ）」の 3 つを<b>同一のエラーコード</b>に
     * 畳み込む。片方だけ別応答になると ID の実在・可視性が応答差分から漏れる。</p>
     *
     * <p>帰属検証の型は同ドメインの {@code AnnouncementRangeTemplateService#update/delete}
     * （{@code findById(...).filter(スコープ一致).orElseThrow(...)}）に揃えている。
     * 可視性検証は一覧側と同一の正準経路（{@link #resolveAllowedVisibilities}）を流用する。</p>
     */
    private void assertReadable(AnnouncementScopeType scopeType, Long scopeId,
                                Long announcementId, Long userId) {
        feedRepository.findById(announcementId)
                // 1. 帰属検証（他テナントの ID を自スコープ URL に差し込ませない）
                .filter(feed -> feed.getScopeType() == scopeType && scopeId.equals(feed.getScopeId()))
                // 2. 可視性検証（一覧に出るものと同じ集合。ロール解決はスコープ一致時のみ発火させる）
                .filter(feed -> isReadable(feed, resolveAllowedVisibilities(scopeType, scopeId, userId)))
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_001));
    }

    /**
     * 閲覧者が当該スコープで閲覧できる visibility 値の集合を、<b>一覧側と同一の正準経路</b>で解決する。
     *
     * <p>{@code AnnouncementFeedController} / {@code AnnouncementFeedOrgController} が
     * {@link AnnouncementFeedService#getAnnouncementFeed} に渡すのと同じ
     * 「{@link RoleResolver#resolveViewerRole} → {@link AnnouncementVisibility#allowedFor}」の
     * 2 段である。一覧に出る集合と既読にできる集合を同一に保つのが要件であり、
     * ここで独自の可視性述語を書いてはならない。</p>
     *
     * <p><b>fail-closed</b>: COMMITTEE / ADVERTISER_AD は既読 EP の入口を持たない
     * （Controller は TEAM / ORGANIZATION のみ渡す）。将来入口が増えたときに無検証で素通り
     * しないよう<b>空集合</b>（＝何も可視でない）を返す。単件既読では ANNOUNCE_001 に、
     * 一括既読では「1 件も既読化しない」に落ちる。なお {@link RoleResolver} の下流
     * （{@code AccessControlService#getRoleName}）は TEAM / ORGANIZATION 以外の scopeType を
     * 受け取ると {@code IllegalArgumentException}（500）になるため、この分岐は必須である。</p>
     */
    private Set<String> resolveAllowedVisibilities(AnnouncementScopeType scopeType, Long scopeId, Long userId) {
        if (scopeType != AnnouncementScopeType.TEAM && scopeType != AnnouncementScopeType.ORGANIZATION) {
            return Set.of();
        }
        ViewerRole viewerRole = roleResolver.resolveViewerRole(userId, scopeType.name(), scopeId);
        return AnnouncementVisibility.allowedFor(viewerRole.name());
    }

    /**
     * 当該フィードが閲覧者に可視かを判定する（＝既読にしてよいか）。
     *
     * <p><b>単件既読専用</b>（一括既読は #2494 で DB 側の
     * {@link AnnouncementFeedQueryRepository#findUnreadIdsByScope} に寄せたため、
     * この Java 述語を通らない）。条件は一覧クエリ
     * {@link AnnouncementFeedQueryRepository#findByScope} の WHERE 句
     * （両クエリが共有する正準定数）と完全に同一でなければならない:</p>
     * <ul>
     *   <li>{@code sourceDeletedAt IS NULL}（元コンテンツ削除済みは一覧に出ない）</li>
     *   <li>{@code expiresAt IS NULL OR expiresAt > 現在時刻}（期限切れは一覧に出ない）</li>
     *   <li>{@code visibility IN (閲覧者が閲覧できる集合)}</li>
     * </ul>
     *
     * <p>削除済み・期限切れの 2 条件は {@code AnnouncementInboxAdapter#isVisibleTo} とも一致する。
     * これらを見ないと「一覧に出ないお知らせを既読化でき、応答差分から実在も判別できる」
     * という同一ドメイン内の挙動割れが残る。</p>
     */
    private boolean isReadable(AnnouncementFeedEntity feed, Set<String> allowedVisibilities) {
        if (feed.getSourceDeletedAt() != null) {
            return false;
        }
        if (feed.getExpiresAt() != null && !feed.getExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }
        return allowedVisibilities.contains(feed.getVisibility());
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー: 既読 ID セット取得（N+1 防止）
    // ═════════════════════════════════════════════════════════════

    /**
     * ユーザーが既読しているフィード ID のセットをバッチ取得する（N+1 防止）。
     *
     * <p>従来は「N+1 防止」と謳いながら feed 件数ぶん
     * {@code findByAnnouncementFeedIdAndUserId} をループ発行しており、名と実が食い違っていた。
     * {@code IN} 句のバッチ版 {@link AnnouncementReadStatusRepository#findByUserIdAndAnnouncementFeedIdIn}
     * に置き換え、feed 件数に依らず 1 クエリで解決する
     * （{@code AnnouncementInboxAdapter} が既に採っている型に合わせた）。</p>
     *
     * @param userId  ユーザー ID
     * @param feedIds フィード ID リスト
     * @return 既読済みフィード ID セット
     */
    public Set<Long> fetchReadFeedIds(Long userId, List<Long> feedIds) {
        if (feedIds.isEmpty()) {
            return Set.of();
        }
        return readStatusRepository.findByUserIdAndAnnouncementFeedIdIn(userId, feedIds).stream()
                .map(AnnouncementReadStatusEntity::getAnnouncementFeedId)
                .collect(Collectors.toSet());
    }
}
