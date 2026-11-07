package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 時給削除の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>時給削除には認可が必要だが、{@link ShiftHourlyRateService} はクラスに {@code @Transactional} が付いているため、
 * そこに認可付きの削除を置くと membership / role の Repository が削除 TX の内側から到達され、
 * ドメイン横断 TX の番人（D-3T）に新たな違反を生む。そこで認可は本クラス（{@code @Transactional} を
 * クラスにもメソッドにも付けない）で行い、削除は {@link ShiftHourlyRateRepository} 自身の TX に任せる。
 * {@link ShiftHourlyRateService} には削除メソッドを置かない（認可なしの削除入口を残さない）。</p>
 *
 * <p>認可の判定は {@link ShiftHourlyRateAuthorizer} に一本化しており、作成・参照と同一の判定を通る。
 * 対象行が存在しない場合も権限なしと同じ 403（COMMON_002）とし、存在の有無を漏らさない。
 * 名前を {@code *AccessService} 等にしないのは、呼んだだけで認可シグナル扱いになるのを避けるため。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftHourlyRateDeletionFacade {

    private final ShiftHourlyRateRepository hourlyRateRepository;
    private final ShiftHourlyRateAuthorizer hourlyRateAuthorizer;

    /**
     * 時給設定を削除する。
     *
     * @param rateId        時給設定ID
     * @param currentUserId 操作ユーザーID（認可判定に使用）
     * @throws BusinessException 権限がない、または対象が存在しない場合（COMMON_002 / 403）
     */
    public void deleteHourlyRate(Long rateId, Long currentUserId) {
        ShiftHourlyRateEntity entity = hourlyRateRepository.findById(rateId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));
        Long targetUserId = entity.getUserId();
        Long teamId = entity.getTeamId();

        hourlyRateAuthorizer.checkHourlyRateAccess(currentUserId, targetUserId, teamId);

        hourlyRateRepository.deleteById(rateId);
        log.info("時給設定削除: id={}, userId={}, teamId={}", rateId, targetUserId, teamId);
    }
}
