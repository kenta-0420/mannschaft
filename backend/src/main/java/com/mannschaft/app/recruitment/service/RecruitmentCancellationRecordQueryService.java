package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowSourceRef;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSlice;
import com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F03.11.1 募集キャンセル料の記録一覧（設計書 §12・免除 UI のための一覧）。
 *
 * <p><b>裁定（マスター 2026-08-13）</b>: 一覧の対象は<b>受取先側</b>（精算管理者・受取先本人）と
 * {@code SYSTEM_ADMIN} に限る。<b>債務者（キャンセル料を負っている本人）向けの一覧は本波では作らない</b>
 * ——「自分がなぜ申込できないのか分からない」という導線の課題は実在するが、それは免除の操作を
 * 可能にする本任務とは目的が異なる別の画面の話であり、混ぜると両方が中途半端になる。</p>
 *
 * <h2>受取先の権威は payment ドメイン（escrow）にある</h2>
 *
 * <p>一覧に出す記録の受取先は、<b>必ず {@code ConnectChargeService#filterPayeeSettlementManaged}
 * （escrow の payee）で判定する</b>。{@code recruitment_listings} が持つ
 * {@code payeeKind}/{@code payeeUserId}/{@code scopeId} は<b>募集の作成後に変更できる可変の値</b>であり、
 * 権威にはできない——受取先を差し替えると、変更後の受取先に従前の記録（債務者 ID・金額・状態）が
 * 見えてしまう。判定は免除 API が使う {@code isPayeeSettlementManager} と<b>同一の実装</b>
 * （payment 側で共通化済み）を通るため、閲覧と実行で判断が割れることはない。</p>
 *
 * <p>listing ベースの絞り込みは<b>安価な事前絞り込みとして残している</b>
 * （{@code RecruitmentCancellationRecordRepository#findChunkOfPayeeCandidates}）。
 * これは権威ある判定へ渡す候補を DB 側で安く減らす<b>最適化であって権威ではない</b>。
 * 事前絞り込みを通った行も、最後は必ず escrow 基準の判定で絞られる。</p>
 *
 * <h2>ドメイン境界</h2>
 *
 * <p>recruitment から escrow のテーブル／Repository は読まない。payment ドメインが公開する
 * 入口メソッド 1 本を呼び、{@link EscrowSourceRef} の集合（自分が渡した識別子のうち許されたもの）
 * だけを受け取る（§3.4 のクロスドメイン禁止・D-5 番人）。</p>
 *
 * <h2>ページング</h2>
 *
 * <p><b>キーセットページング</b>（{@code (cancelledAt, id)} の一意な複合キー）を採る。OFFSET
 * ページングは本件では成立しない。理由は 2 つあり、どちらか一方でも致命である:</p>
 * <ol>
 *   <li><b>母集合が縮む</b> — 既定の絞り込み（免除可能な 3 状態）では、免除した行がその場で
 *       絞り込みから外れる。ページ番号を進める方式だと、縮んだぶんだけ後続の行が OFFSET の網から
 *       漏れて読み飛ばされる。</li>
 *   <li><b>アプリ層で後段の絞り込みが入る</b> — DB から取った 1 ページを escrow 基準で絞るため、
 *       DB の「N 件目から」とアプリの「N 件目」が一致しない。OFFSET はそもそも意味を持たない。</li>
 * </ol>
 *
 * <p>「ページ 0 固定のドレイン」は採らない。それが正しいのは<b>処理した行が必ず絞り込みから外れる</b>
 * 場合に限られるが、本 EP は {@code status} を指定して {@code WAIVED}/{@code PAID} を閲覧でき、
 * その場合は免除しても行が母集合に残り続ける（ページ 0 を返し続けて先へ進めない）。</p>
 */
@Service
@RequiredArgsConstructor
public class RecruitmentCancellationRecordQueryService {

    /** 免除可能な状態のみを既定で返す（§10.1 の対象状態表）。 */
    private static final List<CancellationPaymentStatus> DEFAULT_STATUSES = List.of(
            CancellationPaymentStatus.PENDING,
            CancellationPaymentStatus.FAILED,
            CancellationPaymentStatus.UNCOLLECTIBLE);

    /** JPQL の {@code IN ()} 空リストを避けるための番人値（存在しない ID）。 */
    private static final Long SENTINEL_ID = -1L;

    /** {@code ConnectChargeService}/{@code ConnectAccountService} の定義（"MANAGE_RECRUITMENTS"）と同一。 */
    private static final String PERMISSION_MANAGE_PAYMENT = "MANAGE_RECRUITMENTS";

    /**
     * 先頭ページのカーソル番人（どの実データよりも新しい位置）。
     *
     * <p>型は瞬間（{@link OffsetDateTime}）で扱う。DTO の {@code cancelledAt} と同じ土俵に
     * 揃えるためであり、DB へ渡すときだけサーバの業務ゾーンで壁時計へ落とす。</p>
     */
    private static final OffsetDateTime CURSOR_HEAD_CANCELLED_AT =
            LocalDateTime.of(9999, 12, 31, 23, 59, 59)
                    .atZone(UserZoneLocalDateTimeParser.SERVER_ZONE)
                    .toOffsetDateTime();

    /** 先頭ページのカーソル番人（{@code cancelledAt} 同値時の id 比較用）。 */
    private static final Long CURSOR_HEAD_ID = Long.MAX_VALUE;

    /**
     * 1 ページを満たすための DB チャンク取得の上限回数（安全弁）。
     *
     * <p>権威ある絞り込みで大半が落ちる利用者に対し、DB を延々と走査し続けないための打ち切り。
     * 打ち切った場合は「続きがある」として返し、続きの取得は次のリクエストに委ねる
     * （無限ループにも全件走査にもしない）。</p>
     */
    private static final int MAX_CHUNKS_PER_PAGE = 20;

    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;
    private final AccessControlService accessControlService;
    private final ConnectChargeService connectChargeService;

    /**
     * キャンセル料記録の一覧を取得する（受取先側の管理者・本人・{@code SYSTEM_ADMIN} 向け）。
     *
     * @param actorUserId 操作者ユーザー ID
     * @param statuses    絞り込む決済ステータス（未指定なら免除可能な既定 3 状態）
     * @param cursor      前回の {@code nextCursor}（先頭ページは {@code null}）
     * @param limit       このページで返す最大件数
     * @return 操作者が<b>escrow 上の</b>受取先である記録のみを含むスライス（{@code SYSTEM_ADMIN} は全件）
     */
    @Transactional(readOnly = true)
    public RecruitmentCancellationRecordSlice list(
            Long actorUserId, Collection<CancellationPaymentStatus> statuses, String cursor, int limit) {

        List<CancellationPaymentStatus> effectiveStatuses =
                (statuses == null || statuses.isEmpty()) ? DEFAULT_STATUSES : List.copyOf(statuses);

        Cursor position = parseCursor(cursor);

        // SYSTEM_ADMIN は全件見える（AccessControlService への直接呼び出し＝認可番人シグナル）。
        boolean systemAdmin = accessControlService.isSystemAdmin(actorUserId);

        // 事前絞り込み（最適化）用のスコープ集合。SYSTEM_ADMIN は事前絞り込み自体を使わない。
        Set<Long> teamScopeIds = systemAdmin ? Set.of() : resolveManagedTeamScopeIds(actorUserId);
        Set<Long> orgScopeIds = systemAdmin ? Set.of() : resolveManagedOrgScopeIds(actorUserId);

        List<RecruitmentCancellationRecordSummaryResponse> collected = new ArrayList<>();
        boolean hasNext = false;
        String nextCursor = null;

        for (int chunkNo = 0; chunkNo < MAX_CHUNKS_PER_PAGE; chunkNo++) {
            List<RecruitmentCancellationRecordSummaryResponse> chunk = systemAdmin
                    ? cancellationRecordRepository.findChunkForSystemAdmin(
                            effectiveStatuses, position.toWallClock(), position.id(), PageRequest.of(0, limit))
                    : cancellationRecordRepository.findChunkOfPayeeCandidates(
                            actorUserId,
                            teamScopeIds.isEmpty() ? Set.of(SENTINEL_ID) : teamScopeIds,
                            orgScopeIds.isEmpty() ? Set.of(SENTINEL_ID) : orgScopeIds,
                            effectiveStatuses, position.toWallClock(), position.id(), PageRequest.of(0, limit));

            if (chunk.isEmpty()) {
                break;
            }

            // 権威ある受取先（escrow）で絞る。SYSTEM_ADMIN は受取先に関わらず全件見えるため対象外。
            Set<EscrowSourceRef> visible = systemAdmin ? null : resolveVisibleRefs(chunk, actorUserId);

            boolean filled = false;
            for (RecruitmentCancellationRecordSummaryResponse row : chunk) {
                // カーソルは「検査し終えた位置」を指す。行を捨てても前進させる（同じ行を二度検査しない）。
                position = new Cursor(row.getCancelledAt(), row.getId());
                if (visible != null
                        && !visible.contains(new EscrowSourceRef(row.getListingId(), row.getParticipantId()))) {
                    continue;
                }
                collected.add(row);
                if (collected.size() >= limit) {
                    filled = true;
                    break;
                }
            }
            if (filled) {
                hasNext = true;
                nextCursor = position.encode();
                break;
            }
            if (chunk.size() < limit) {
                // DB 側にこれ以上の行が無い（要求より少ない件数しか返らなかった）。
                break;
            }
            if (chunkNo == MAX_CHUNKS_PER_PAGE - 1) {
                // 安全弁で打ち切る。続きの判断は次のリクエストへ委ねる。
                hasNext = true;
                nextCursor = position.encode();
            }
        }

        return new RecruitmentCancellationRecordSlice(List.copyOf(collected), nextCursor, hasNext);
    }

    /**
     * チャンク内の各行について、操作者が<b>escrow 上の</b>受取先側の精算管理者である三つ組を解決する。
     *
     * <p>payment ドメインの入口を<b>チャンクにつき 1 回</b>だけ呼ぶ（行ごとに呼ぶと、ページ内の
     * 件数ぶんのラウンドトリップになる）。escrow を引けない行は誰にも見えない
     * ——受取先が特定できない以上、受取先側の権限は誰にも与えられない（§10.2 の最終行と同じ扱い）。</p>
     */
    private Set<EscrowSourceRef> resolveVisibleRefs(
            List<RecruitmentCancellationRecordSummaryResponse> chunk, Long actorUserId) {
        Set<EscrowSourceRef> refs = new LinkedHashSet<>();
        for (RecruitmentCancellationRecordSummaryResponse row : chunk) {
            refs.add(new EscrowSourceRef(row.getListingId(), row.getParticipantId()));
        }
        return connectChargeService.filterPayeeSettlementManaged(
                EscrowSourceKind.RECRUITMENT, refs, actorUserId);
    }

    /**
     * 操作者が支払い管理権限（{@value #PERMISSION_MANAGE_PAYMENT}）を持つ TEAM の scopeId 集合を返す。
     *
     * <p>候補（操作者自身の所属 TEAM。件数は操作者の所属数に依存し記録数には依存しない）を
     * {@link AccessControlService#findAffiliatedScopeIds} で絞ってから権限を検査するため、
     * 記録件数分のクエリは発生しない。</p>
     *
     * <p>これは<b>事前絞り込み（最適化）のための集合</b>であり認可の権威ではない
     * （権威は escrow 側。クラス Javadoc 参照）。</p>
     */
    private Set<Long> resolveManagedTeamScopeIds(Long actorUserId) {
        Set<Long> candidates = accessControlService.findAffiliatedScopeIds(
                actorUserId, RecruitmentScopeType.TEAM.name());
        Set<Long> managed = new LinkedHashSet<>();
        for (Long teamId : candidates) {
            if (accessControlService.hasPermission(
                    actorUserId, teamId, RecruitmentScopeType.TEAM.name(), PERMISSION_MANAGE_PAYMENT)) {
                managed.add(teamId);
            }
        }
        return managed;
    }

    /**
     * 操作者が管理者または支払い管理権限を持つ ORG の scopeId 集合を返す。
     *
     * <p>これは<b>事前絞り込み（最適化）のための集合</b>であり認可の権威ではない
     * （権威は escrow 側。クラス Javadoc 参照）。</p>
     */
    private Set<Long> resolveManagedOrgScopeIds(Long actorUserId) {
        Set<Long> candidates = accessControlService.findAffiliatedScopeIds(
                actorUserId, RecruitmentScopeType.ORGANIZATION.name());
        Set<Long> managed = new LinkedHashSet<>();
        for (Long orgId : candidates) {
            try {
                accessControlService.checkAdminOrHasPermission(
                        actorUserId, orgId, RecruitmentScopeType.ORGANIZATION.name(), PERMISSION_MANAGE_PAYMENT);
                managed.add(orgId);
            } catch (BusinessException e) {
                // 権限が無いだけであり異常ではない（判定であって認可の実行ではない）。
            }
        }
        return managed;
    }

    /**
     * カーソル文字列を解釈する。{@code null}／空文字は先頭ページ。
     *
     * <p>壊れたカーソルは 400 で返す（{@code IllegalArgumentException} を素通りさせて 500 にしない）。</p>
     */
    private Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(CURSOR_HEAD_CANCELLED_AT, CURSOR_HEAD_ID);
        }
        int separator = cursor.lastIndexOf('_');
        if (separator <= 0 || separator == cursor.length() - 1) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        try {
            return new Cursor(
                    OffsetDateTime.parse(cursor.substring(0, separator)),
                    Long.valueOf(cursor.substring(separator + 1)));
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    /**
     * キーセットページングの位置（{@code (cancelledAt, id)} の複合・一意）。
     *
     * <p>位置は瞬間（{@link OffsetDateTime}）で保持し、DB へ渡すときだけ
     * {@link #toWallClock()} でサーバの業務ゾーンの壁時計へ落とす（列が壁時計型のため）。
     * 往復の変換をこの型に閉じ込め、呼び出し側にゾーンの判断を持ち出さない。</p>
     *
     * @param cancelledAt 直前に検査し終えた行の {@code cancelledAt}
     * @param id          直前に検査し終えた行の {@code id}（{@code cancelledAt} 同値時の決着に使う）
     */
    private record Cursor(OffsetDateTime cancelledAt, Long id) {
        String encode() {
            return cancelledAt + "_" + id;
        }

        /** DB の {@code cancelled_at}（壁時計型）と比較するための値へ落とす。 */
        java.time.LocalDateTime toWallClock() {
            return cancelledAt.atZoneSameInstant(UserZoneLocalDateTimeParser.SERVER_ZONE).toLocalDateTime();
        }
    }
}
