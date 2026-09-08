package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 退会申請の<b>現在の状態</b>を他ドメインへ公開する auth ドメインの読み取り専用 API
 * （柱③-B・CMP-260901-1538 PR-3／Codex 検分2巡目 P1-1・P1-2）。
 *
 * <h2>なぜ必要か</h2>
 * <p>退会（{@code WithdrawalRequestedEvent}）と退会取消（{@code WithdrawalCancelledEvent}）は、どちらも
 * 永続化されない Spring のインメモリイベントで、共用 {@code event-pool} 上の非同期処理として走る。
 * <b>イベントの到達順は保証されない</b>。退会受付の直後に取り消すと、取消処理が先に走って対象ゼロで
 * 終わり、そのあとに届いた古い退会イベントが期末解約と引継要求を作ってしまう。</p>
 *
 * <p>これを塞ぐ唯一の正しい方法は、<b>イベントの中身ではなく処理時点の DB の真値を見る</b>ことである。
 * 各処理は自分のトランザクションの中で「今この人は退会申請中か」を本サービスに問い合わせ、
 * 答えに反する処理は行わない。</p>
 *
 * <h2>ドメイン境界</h2>
 * <p>payment / gdpr から auth の {@code UserRepository} を直接引くとクロスドメイン Repository 依存
 * （{@code CrossDomainRepositoryDependencyArchTest} D-5）になる。CLAUDE.md の「ドメイン間は ID 参照＋
 * Service 経由のみ」に従い、本サービスを唯一の窓口にする。</p>
 */
@Service
@RequiredArgsConstructor
public class WithdrawalStateQueryService {

    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * 指定ユーザーが<b>いま退会申請中</b>なら、その退会試行の世代（{@code users.deleted_at}）を返す。
     *
     * <p>戻り値が空なら「退会申請中ではない」（未申請・または取消済み）。この1つの問い合わせが
     * 「退会処理を進めてよいか」と「どの退会試行に属する作業か」の両方に答える。</p>
     *
     * <p>{@code users.deleted_at} は退会申請のたびに新しい値が入り、取消で NULL に戻るため、
     * 退会試行の世代としてそのまま使える（別途 ID 列を増やす必要がない）。</p>
     *
     * @param userId 対象ユーザー
     * @return 退会申請中ならその申請時刻、そうでなければ空
     */
    @Transactional(readOnly = true)
    public Optional<Instant> findPendingWithdrawalAttempt(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        // UserEntity には @SQLRestriction("deleted_at IS NULL") が付いており、JPQL では
        // 退会申請中のユーザーが1件も返らない（＝常に「申請中でない」と誤答する）。native で読む。
        return userRepository.findDeletedAtIncludingDeleted(userId).map(this::toInstant);
    }

    /**
     * 同上を<b>ユーザー行をロックして</b>読む（Codex 検分4巡目 P1-3）。
     *
     * <p>ロックなしの確認では「真値を見た直後・自分が書き込む前」に {@code cancelWithdrawal} や
     * 再退会が commit できてしまい、<b>確認と反映が線形化しない</b>。退会状態を根拠に DB を書き換える
     * 経路（期末解約の着手／確定、引継要求の作成／終端化、予約の解除）は必ず本メソッドを使い、
     * 呼び出し元のトランザクションが終わるまでユーザーの退会状態を固定する。</p>
     *
     * <p><b>ロック順序の正準は users → membership_subscriptions →
     * membership_payer_withdrawal_cancellations</b>。本メソッドが最初に来るため、
     * 呼び出し元はこの順を守れば相互デッドロックしない。</p>
     */
    @Transactional
    public Optional<Instant> lockAndFindPendingWithdrawalAttempt(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findDeletedAtForUpdateIncludingDeleted(userId).map(this::toInstant);
    }

    /**
     * 退会申請中のユーザー ID を返す（PR-4 の再照合バッチの起点・Codex 検分2巡目 P1-2）。
     *
     * <p>作業行（{@code membership_payer_withdrawal_cancellations}）が<b>そもそも作られなかった</b>ケース
     * ——退会本体の commit 後・非同期タスク開始前の停止、{@code event-pool} の投入拒否、複数契約の途中での
     * 停止——は、作業行を走査しても永久に拾えない。退会状態そのものを起点にすれば、行の有無に関係なく
     * 「まだ解約されていない継続課金」を再構築できる。</p>
     *
     * <p>退会申請から30日で物理 purge されるため、この集合は運用上有界である。</p>
     */
    @Transactional(readOnly = true)
    public List<Long> findUserIdsWithPendingWithdrawal() {
        return userRepository.findIdsByDeletedAtIsNotNull();
    }

    /** {@code users.deleted_at} は legacy な {@link LocalDateTime} 列であるため、アプリの時計帯で解釈する。 */
    private Instant toInstant(LocalDateTime value) {
        return value.atZone(clock.getZone()).toInstant();
    }
}
