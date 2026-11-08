package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * お知らせ既読の<b>書き込み</b>サービス（F02.6）。
 *
 * <p>
 * 既読マークの書き込み（単件・複数 ID）と、既読状態のバッチ取得ヘルパー（N+1 防止）を担う。
 * クラス全体が {@code @Transactional} なので、<b>認可の判定はここでは行わない</b>。
 * 判定（帰属・可視性・宛先・課金。キャッシュなしの役割解決を含む）は {@link AnnouncementReadCommandService}
 * （トランザクションなし）が済ませてから、本クラスの書き込みを呼ぶ（ALIC-1 §2.5。判定の tx 内で他ドメインの
 * Repository に到達させないため）。呼び出せるのは判定部品だけである（ArchUnit F03）。
 * </p>
 *
 * <p>
 * <b>規則は「自分に見えているお知らせなら既読にしてよい」</b>（認可根治「裏目付」第二陣 C-social・マスター御裁可 2026-07-28）。
 * 一覧は非メンバーにも {@code PUBLIC} のお知らせを返す仕様なので、既読を在籍で絞ると「見えているのに既読にできない」不整合が生じる。
 * 逆に在籍だけを見ると、応援者が一覧に出ない内輪限定を既読化でき、応答差分から実在も判別できる。
 * </p>
 *
 * <p>
 * <b>存在秘匿</b>: 「当該スコープに属さない」「そもそも存在しない」「自分には可視でない」はいずれも
 * {@link AnnouncementErrorCode#ANNOUNCE_001} に畳み込み、応答差分から ID の実在・可視性が漏れないようにする。
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
     * <p>1 回の未読抽出クエリ / 1 本の {@code INSERT} 文で扱う最大件数。
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
    private final AnnouncementReadStatusRepository readStatusRepository;
    private final ProxyInputContext proxyInputContext;
    private final AnnouncementCreationService creationService;

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
     * <p>
     * <b>同時実行での冪等性（#2530 ⑤）</b>: 事前の存在確認は「代理確認の証跡を二重に作らない」
     * ためのもので、<b>競合の防止にはならない</b>（確認と挿入の間に窓がある）。同一利用者が
     * 単件既読を同時に 2 回叩いた場合の {@code uq_ars_feed_user} 違反は
     * {@link AnnouncementReadStatusRepository#insertReadStatusesIgnoringExisting} が
     * DB 側で吸収する（例外の握りつぶしではなく、DB 制約に「既読済みなら何もしない」を教えている）。
     * </p>
     *
     * @param scopeType      スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId        スコープ ID（teams.id または organizations.id）
     * @param announcementId お知らせフィード ID
     * @param userId         ユーザー ID
     */
    @Transactional
    public void markAsRead(AnnouncementScopeType scopeType, Long scopeId, Long announcementId, Long userId) {
        // 認可の判定（可視性・宛先・課金）は AnnouncementReadCommandService が tx の外で済ませている。
        // ここは social 内で閉じる確認だけを残す: スコープ帰属・元の未削除・期限内。
        // 「属さない」「存在しない」「削除済み・期限切れ」はいずれも ANNOUNCE_001 に畳み込む（存在秘匿）。
        assertBelongsAndLive(scopeType, scopeId, announcementId);

        // 冪等（早期リターン）: 既読済みなら書き込みも代理証跡の作成もしない。
        // 競合時の最後の砦は下の UPSERT 側（この分岐はレースを塞ぐものではない）。
        boolean alreadyRead = readStatusRepository
                .findByAnnouncementFeedIdAndUserId(announcementId, userId)
                .isPresent();
        if (alreadyRead) {
            return;
        }

        // 既読行の作成は DB 側で冪等な UPSERT を通す（同時実行で 500 にしない・#2530 ⑤）
        readStatusRepository.insertReadStatusesIgnoringExisting(userId, List.of(announcementId));

        // 代理確認の場合: proxy_input_records を作成し、is_proxy_confirmed フラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = creationService.buildAndSaveAnnouncementProxyRecord(
                    "ANNOUNCEMENT_READ", announcementId);
            readStatusRepository.markProxyConfirmed(announcementId, userId, proxyRecord.getId());
        }

        log.debug("既読マーク完了 announcementId={}, userId={}", announcementId, userId);
    }

    // ═════════════════════════════════════════════════════════════
    // 2.6 複数 ID の既読（read-all のチャンク・read-batch の書き込み）
    // ═════════════════════════════════════════════════════════════

    /**
     * 判定済みの feed ID 群を既読にする（1 回 = 1 tx = 1 本の UPSERT）。
     *
     * <p>read-all は 500 件のチャンクごと、read-batch は最大 50 件を 1 回で、{@link AnnouncementReadCommandService} が
     * <b>判定を終えてから</b>呼ぶ。ここでは認可の判定をしない（呼び出せるのは判定部品だけ。ArchUnit F03）。
     * 到達する Repository は social の {@link AnnouncementReadStatusRepository} のみで、tx 内にキャッシュなしの役割解決や
     * 他ドメインの Repository を持ち込まない。</p>
     *
     * <p>既読行の作成は DB 側で冪等な UPSERT（{@code INSERT ... SELECT ... ON DUPLICATE KEY UPDATE}）を通す。
     * 既読済みの行の {@code read_at} は変えない。件数は戻り値（MySQL の更新行数は重複行を数えるので使わない）ではなく、
     * 書き込み前に引いた既読済み ID との差で数える。</p>
     *
     * @param userId  ユーザー ID
     * @param feedIds 判定を通った feed ID（重複は除く）
     * @return このとき新たに既読になった件数（既読済みだったものは含まない）
     */
    @Transactional
    public int markFeedIdsAsRead(Long userId, Collection<Long> feedIds) {
        if (feedIds == null || feedIds.isEmpty()) {
            return 0;
        }
        List<Long> ids = feedIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return 0;
        }
        Set<Long> alreadyRead = fetchReadFeedIds(userId, ids);
        List<Long> unread = ids.stream().filter(id -> !alreadyRead.contains(id)).toList();
        if (unread.isEmpty()) {
            return 0;
        }
        readStatusRepository.insertReadStatusesIgnoringExisting(userId, unread);
        return unread.size();
    }

    /**
     * 一括既読の結果（#2530 ①）。
     *
     * <p>従来 {@code markAllAsRead} は件数だけを返し、Controller はそれを伝搬せず
     * ハードコードの {@code 0} を応答していた。加えて防御上限で打ち切ったことも
     * WARN ログにしか出ていなかったため、FE は「未読 0」と表示しつつ実際には未読が
     * 残るという食い違いが起きていた。<b>件数と残余の有無を対で返す</b>ことで、
     * 画面が実体と食い違わないようにする。</p>
     *
     * @param markedCount   このリクエストで既読化した件数（既読済みだったものは含まない）
     * @param hasMoreUnread 防御上限で打ち切り、未読が残っているか
     */
    public record MarkAllReadOutcome(int markedCount, boolean hasMoreUnread) {

        /** 未読を最後まで処理しきった結果。 */
        static MarkAllReadOutcome completed(int markedCount) {
            return new MarkAllReadOutcome(markedCount, false);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // social 内で閉じる確認
    // ═════════════════════════════════════════════════════════════

    /**
     * 対象お知らせが当該スコープに帰属し、元が削除されておらず、期限内であることを確認する。違反時は
     * {@link AnnouncementErrorCode#ANNOUNCE_001}（存在しない・越境・削除済み・期限切れを同一コードに畳む）。
     *
     * <p>可視性・宛先・課金の判定は呼び出し元の判定部品が済ませている。これらの条件は一覧クエリ
     * （{@link AnnouncementFeedQueryRepository#findByScope}）の WHERE 句・{@code AnnouncementInboxAdapter#isVisibleTo} と
     * 揃えてある。</p>
     */
    private void assertBelongsAndLive(AnnouncementScopeType scopeType, Long scopeId, Long announcementId) {
        feedRepository.findById(announcementId)
                // 帰属検証（他テナントの ID を自スコープ URL に差し込ませない）
                .filter(feed -> feed.getScopeType() == scopeType && scopeId.equals(feed.getScopeId()))
                .filter(feed -> feed.getSourceDeletedAt() == null)
                .filter(feed -> feed.getExpiresAt() == null || feed.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new BusinessException(AnnouncementErrorCode.ANNOUNCE_001));
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
