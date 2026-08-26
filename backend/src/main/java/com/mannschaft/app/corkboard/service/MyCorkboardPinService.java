package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.CorkboardMapper;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F09.8.1 個人コルクボードのピン止め切替サービス。
 *
 * <p>個人スコープ（{@code scope_type = PERSONAL}）のボードに属するカードに対し、
 * 所有者本人のみピン止め状態をトグルできる。チーム/組織スコープのボード上のカードに対する
 * ピン操作は v1.0 では拒否する。</p>
 *
 * <p>主な検証項目:</p>
 * <ol>
 *   <li>認可: 所有者本人 かつ 個人スコープのボードであること
 *       （{@link CorkboardAccessGuard#requirePinnableOwnBoard}・違反は
 *       {@link CorkboardErrorCode#PIN_PERSONAL_ONLY}）</li>
 *   <li>カード存在・論理削除確認（{@link CorkboardErrorCode#CARD_NOT_FOUND}）</li>
 *   <li>アーカイブ済みカードへの pin 拒否（{@link CorkboardErrorCode#PIN_ARCHIVED_NOT_ALLOWED}）</li>
 *   <li>ユーザー単位ピン上限チェック（{@link CorkboardErrorCode#PIN_LIMIT_EXCEEDED}、上限 50 枚）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MyCorkboardPinService {

    /** 1 ユーザーあたりのピン止めカード上限（個人ダッシュボードの可読性確保のため）。 */
    static final int MAX_PINNED_PER_USER = 50;

    private final CorkboardCardRepository cardRepository;
    private final CorkboardMapper corkboardMapper;
    private final CorkboardAccessGuard corkboardAccessGuard;

    /**
     * カードのピン止め状態を切り替える（F09.8 件3' 追補: 付箋メモ・色を併せて受ける）。
     *
     * <p>後方互換のため引数 {@code userNote} / {@code noteColor} は {@code null} 許容。
     * pin=true 時のみ、明示的に {@code null} 以外で渡された値を上書き保存する。
     * アンピン時 ({@code newIsPinned=false}) は {@code userNote} / {@code noteColor} を
     * 送っても触らない（再ピン時に同じ付箋を復元できるように残す方針）。</p>
     *
     * @param boardId     対象ボードID
     * @param cardId      対象カードID
     * @param newIsPinned true でピン止め、false で解除
     * @param userNote    pin 時に書き込む付箋メモ本文（null なら更新しない）
     * @param noteColor   pin 時に書き込む付箋色（null なら更新しない＝カラーラベルと同色扱い）
     * @param userId      操作ユーザーID（所有者検証に使用）
     * @return 更新後のカードレスポンス
     */
    public CorkboardCardResponse togglePin(Long boardId, Long cardId, boolean newIsPinned,
                                            String userNote, String noteColor, Long userId) {
        // 1) 認可: 所有者本人の個人ボードであることを要求する（owner_id 条件付きクエリ +
        //    スコープ検証。他者所有・不存在・非個人スコープは同一エラーへ正規化）
        corkboardAccessGuard.requirePinnableOwnBoard(userId, boardId);

        // 2) カード取得 + ボード一致 + 論理削除確認
        //    findByIdAndCorkboardId は @SQLRestriction("deleted_at IS NULL") により削除済みを自動除外する
        CorkboardCardEntity card = cardRepository.findByIdAndCorkboardId(cardId, boardId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.CARD_NOT_FOUND));

        // 4) アーカイブ済みカードは pin 不可（unpin は許可：ユーザーが自発的にクリーンアップできるよう）
        if (newIsPinned && Boolean.TRUE.equals(card.getIsArchived())) {
            throw new BusinessException(CorkboardErrorCode.PIN_ARCHIVED_NOT_ALLOWED);
        }

        // 5) 上限チェック（pin 操作のみ。既に pin されたカードへの再 pin はスキップ）
        if (newIsPinned && !Boolean.TRUE.equals(card.getIsPinned())) {
            int currentPinned = cardRepository.countPinnedByOwnerIdAndScopePersonal(userId);
            if (currentPinned >= MAX_PINNED_PER_USER) {
                throw new BusinessException(CorkboardErrorCode.PIN_LIMIT_EXCEEDED);
            }
        }

        // 6) 状態更新
        card.pin(newIsPinned);
        // 6.5) F09.8 件3': pin 時のみ付箋メモ・色を上書き（null は無変更、アンピン時は触らない）
        if (newIsPinned) {
            card.updatePinNote(userNote, noteColor);
        }
        CorkboardCardEntity saved = cardRepository.save(card);
        log.info("カードピン止め切替: userId={}, boardId={}, cardId={}, isPinned={}, hasNote={}, hasColor={}",
                userId, boardId, cardId, newIsPinned,
                userNote != null, noteColor != null);
        return corkboardMapper.toCardResponse(saved);
    }

    /**
     * 後方互換のための旧シグネチャ（{@code userNote}/{@code noteColor} 未指定）。
     * 内部で新シグネチャに委譲する。新規呼び出しは新シグネチャを使うこと。
     *
     * @deprecated F09.8 件3' 以降は {@link #togglePin(Long, Long, boolean, String, String, Long)} を使う
     */
    @Deprecated
    public CorkboardCardResponse togglePin(Long boardId, Long cardId, boolean newIsPinned, Long userId) {
        return togglePin(boardId, cardId, newIsPinned, null, null, userId);
    }
}
