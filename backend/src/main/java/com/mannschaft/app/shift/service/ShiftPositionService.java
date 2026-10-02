package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.dto.CreatePositionRequest;
import com.mannschaft.app.shift.dto.ShiftPositionResponse;
import com.mannschaft.app.shift.dto.UpdatePositionRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフトポジションの <b>トランザクション本体</b>（自ドメイン = shift の Repository だけに触れる）。
 *
 * <p><b>認可はここに置かない（CMP-260923-0954 W1 の作り替え / 認可をトランザクションの外へ）:</b>
 * 権限の確認は非トランザクションの {@link ShiftPositionFacade} が行い、通ったものだけを本クラスの
 * メソッドが実行する。{@code AccessControlService} / {@code ScopeConcealingAccessGate} への依存と
 * 認可用の private メソッドは本クラスに持たない（D-3T 番人と {@code ShiftTxFacadeArchTest} が固定）。
 * 認可の契約（主体 × 結果）は {@link ShiftPositionFacade} の Javadoc を参照。</p>
 *
 * <p><b>scope の解決と読み直し:</b> positionId 指定の更新・削除は、Facade が認可の前に
 * {@link #resolvePositionScope}（readOnly）で<b>ポジション実体由来の teamId</b> を得る（パス変数・クエリの
 * scope 値を鵜呑みにしない＝BOLA 封鎖）。書き込み tx では同じ経路でポジションを読み直し、認可の後に
 * 消えていれば解決時と同じ {@code SHIFT_004}（404）を投げて DB を変えない（K1/K5）。
 * ポジションは親（スケジュール等）を持たず、team は shift 外なので、たどり直す親は無い。
 * ポジションの teamId 列は不変という前提。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftPositionService {

    private final ShiftPositionRepository positionRepository;
    private final ShiftMapper shiftMapper;

    /**
     * ポジションの scope（所属チーム ID）。Entity は返さない。
     *
     * @param teamId 所属チーム ID
     */
    public record PositionScope(Long teamId) { }

    /**
     * positionId から scope を解決する（更新・削除用。Facade が認可の前に呼ぶ readOnly の読み取り）。
     * 不在なら {@code SHIFT_004}（404）。
     *
     * @param positionId ポジションID
     * @return scope
     */
    public PositionScope resolvePositionScope(Long positionId) {
        return new PositionScope(findPositionOrThrow(positionId).getTeamId());
    }

    /**
     * チームのポジション一覧を取得する（認可は {@link ShiftPositionFacade} 済み）。
     *
     * @param teamId チームID
     * @return ポジション一覧
     */
    public List<ShiftPositionResponse> listPositions(Long teamId) {
        List<ShiftPositionEntity> entities = positionRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);
        return shiftMapper.toPositionResponseList(entities);
    }

    /**
     * ポジションを作成する。
     *
     * @param teamId チームID
     * @param req    作成リクエスト
     * @return 作成されたポジション
     * @throws BusinessException 名前の重複（POSITION_NAME_DUPLICATE）
     */
    @Transactional
    public ShiftPositionResponse createPosition(Long teamId, CreatePositionRequest req) {
        // 認可（当該チームの ADMIN 以上）は Facade 済み。
        // 重複チェック
        positionRepository.findByTeamIdAndName(teamId, req.getName())
                .ifPresent(existing -> {
                    throw new BusinessException(ShiftErrorCode.POSITION_NAME_DUPLICATE);
                });

        ShiftPositionEntity entity = ShiftPositionEntity.builder()
                .teamId(teamId)
                .name(req.getName())
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .build();

        entity = positionRepository.save(entity);
        log.info("シフトポジション作成: id={}, teamId={}, name={}", entity.getId(), teamId, entity.getName());
        return shiftMapper.toPositionResponse(entity);
    }

    /**
     * ポジションを更新する。
     *
     * @param positionId ポジションID
     * @param req        更新リクエスト
     * @return 更新されたポジション
     * @throws BusinessException 不在（SHIFT_004 / 404）
     */
    @Transactional
    public ShiftPositionResponse updatePosition(Long positionId, UpdatePositionRequest req) {
        // 認可は Facade 済み。認可の後にポジションが消えた競合を 404 にするため読み直す。
        ShiftPositionEntity entity = findPositionOrThrow(positionId);

        if (req.getName() != null) {
            // 名前変更時は重複チェック
            positionRepository.findByTeamIdAndName(entity.getTeamId(), req.getName())
                    .filter(existing -> !existing.getId().equals(positionId))
                    .ifPresent(existing -> {
                        throw new BusinessException(ShiftErrorCode.POSITION_NAME_DUPLICATE);
                    });
            entity.changeName(req.getName());
        }
        if (req.getDisplayOrder() != null) {
            entity.changeDisplayOrder(req.getDisplayOrder());
        }
        if (req.getIsActive() != null) {
            if (Boolean.TRUE.equals(req.getIsActive())) {
                entity.activate();
            } else {
                entity.deactivate();
            }
        }

        entity = positionRepository.save(entity);
        log.info("シフトポジション更新: id={}", positionId);
        return shiftMapper.toPositionResponse(entity);
    }

    /**
     * ポジションを削除する。
     *
     * @param positionId ポジションID
     * @throws BusinessException 不在（SHIFT_004 / 404）
     */
    @Transactional
    public void deletePosition(Long positionId) {
        // 認可は Facade 済み。認可の後にポジションが消えた競合を 404 にするため読み直す。
        ShiftPositionEntity entity = findPositionOrThrow(positionId);
        positionRepository.delete(entity);
        log.info("シフトポジション削除: id={}", positionId);
    }

    /**
     * ポジションを取得する。存在しない場合は例外をスローする。
     */
    private ShiftPositionEntity findPositionOrThrow(Long id) {
        return positionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_POSITION_NOT_FOUND));
    }
}
