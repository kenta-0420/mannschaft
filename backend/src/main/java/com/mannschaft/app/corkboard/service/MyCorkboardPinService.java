package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.CorkboardMapper;
import com.mannschaft.app.corkboard.dto.CorkboardCardResponse;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
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
 *   <li>ボードの所有者検証（{@link CorkboardErrorCode#PIN_PERSONAL_ONLY}）</li>
 *   <li>スコープ検証（PERSONAL のみ許可）</li>
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

    private static final String SCOPE_PERSONAL = "PERSONAL";

    private final CorkboardRepository boardRepository;
    private final CorkboardCardRepository cardRepository;
    private final CorkboardMapper corkboardMapper;

    /**
     * カードのピン止め状態を切り替える。
     *
     * @param boardId    対象ボードID
     * @param cardId     対象カードID
     * @param newIsPinned true でピン止め、false で解除
     * @param userId     操作ユーザーID（所有者検証に使用）
     * @return 更新後のカードレスポンス
     */
    public CorkboardCardResponse togglePin(Long boardId, Long cardId, boolean newIsPinned, Long userId) {
        // 1) ボード取得 + 所有者検証（owner_id 条件付きクエリで IDOR を構造的に排除）
        CorkboardEntity board = boardRepository.findByIdAndOwnerId(boardId, userId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.PIN_PERSONAL_ONLY));

        // 2) スコープ検証（PERSONAL のみ許可）
        if (!SCOPE_PERSONAL.equals(board.getScopeType())) {
            throw new BusinessException(CorkboardErrorCode.PIN_PERSONAL_ONLY);
        }

        // 3) カード取得 + ボード一致 + 論理削除確認
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
        CorkboardCardEntity saved = cardRepository.save(card);
        log.info("カードピン止め切替: userId={}, boardId={}, cardId={}, isPinned={}",
                userId, boardId, cardId, newIsPinned);
        return corkboardMapper.toCardResponse(saved);
    }
}
