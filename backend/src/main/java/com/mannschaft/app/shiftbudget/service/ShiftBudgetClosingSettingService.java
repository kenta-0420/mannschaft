package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.UuidBytes;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetClosingDayChangeEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetClosingSettingEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetClosingDayChangeRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetClosingSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Function;

/**
 * 組織の締め日設定の参照・変更（CMP-260909-2152 確定設計 r12 §2）。認可・監査は Facade の責務（本クラスは認可も監査も持たない。監査は他ドメインの Repository に届くため TX の外で記録する）。
 *
 * <p>変更は先頭行 {@code FOR UPDATE} 下で受理する。受理条件: (1) expected_revision が現在の revision と一致、(2) 適用開始の期間キーが
 * {@code min_effective_period_key}（今日を含む期間キーと、確定済み期間（旧締めの暦月を含む）の最大キーの次、の遅い方）以上。
 * 違反は 409 SHIFT_BUDGET_038。移行期間（一度だけ短い／長い期間）は許容する。履歴は追記専用。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftBudgetClosingSettingService {

    private final ShiftBudgetClosingSettingRepository settingRepository;
    private final ShiftBudgetClosingDayChangeRepository changeRepository;
    private final ShiftBudgetClosePeriodResolver periodResolver;
    private final ShiftBudgetBusinessDateProvider dateProvider;

    /**
     * 締め日設定の参照ビュー。
     *
     * @param closingDay            今日を含む期間の締め日（0＝末日）
     * @param revision              現在の revision（設定なしは0）
     * @param minEffectivePeriodKey 締め日変更の適用開始の下限（期間キー YYYY-MM）
     * @param changes               変更履歴（revision 昇順）
     */
    public record ClosingSettingView(int closingDay, int revision, String minEffectivePeriodKey, List<ChangeView> changes) {
    }

    /** 変更履歴の1件。 */
    public record ChangeView(int revision, String effectivePeriodKey, int closingDay, int previousClosingDay, String reason,
                             Long actorUserId, Instant createdAt) {
    }

    /** 参照（行を作らない）。 */
    @Transactional(readOnly = true)
    public ClosingSettingView get(Long organizationId) {
        List<ShiftBudgetClosingDayChangeEntity> changes = changeRepository.findByOrganizationIdOrderByRevisionAsc(organizationId);
        int revision = settingRepository.findByOrganizationId(organizationId)
                .map(ShiftBudgetClosingSettingEntity::getRevision).orElse(0);
        return view(organizationId, changes, revision);
    }

    /** 締め日を変更する（履歴を1行追記し revision を +1）。 */
    @Transactional
    public ClosingSettingView change(Long organizationId, Long actorUserId, long expectedRevision, int closingDay,
                                     YearMonth effectiveKey, String reason) {
        // FOR UPDATE を先に取る（既にあれば INSERT IGNORE の共有ロックを経由しない）。無ければ作ってからロックし直す
        if (settingRepository.findByOrganizationIdForUpdate(organizationId).isEmpty()) {
            settingRepository.insertIgnore(UuidBytes.toBytes(UuidV7.generate()), organizationId);
        }
        ShiftBudgetClosingSettingEntity setting = settingRepository.findByOrganizationIdForUpdate(organizationId)
                .orElseThrow(() -> new IllegalStateException("締め日設定の先頭行を確保できません: orgId=" + organizationId));
        List<ShiftBudgetClosingDayChangeEntity> changes = changeRepository.findByOrganizationIdOrderByRevisionAsc(organizationId);

        if (expectedRevision != setting.getRevision()) {
            throw new BusinessException(ShiftBudgetErrorCode.CLOSING_DAY_CHANGE_CONFLICT);
        }
        YearMonth minEffective = minEffective(organizationId, changes);
        if (effectiveKey.isBefore(minEffective)) {
            throw new BusinessException(ShiftBudgetErrorCode.CLOSING_DAY_CHANGE_CONFLICT);
        }

        Function<YearMonth, Integer> fn = ShiftBudgetClosePeriodResolver.closingDayFunction(changes);
        int previous = fn.apply(effectiveKey);
        int nextRevision = setting.getRevision() + 1;
        changeRepository.save(ShiftBudgetClosingDayChangeEntity.builder()
                .settingId(setting.getId())
                .organizationId(organizationId)
                .revision(nextRevision)
                .effectivePeriodKey(effectiveKey.toString())
                .closingDay(closingDay)
                .previousClosingDay(previous)
                .reason(reason)
                .actorUserId(actorUserId)
                .build());
        setting.advanceRevision();
        settingRepository.saveAndFlush(setting);

        return get(organizationId);
    }

    private ClosingSettingView view(Long organizationId, List<ShiftBudgetClosingDayChangeEntity> changes, int revision) {
        Function<YearMonth, Integer> fn = ShiftBudgetClosePeriodResolver.closingDayFunction(changes);
        YearMonth todayKey = ShiftBudgetClosePeriodCalculator.periodKeyOf(dateProvider.today(), fn);
        List<ChangeView> views = changes.stream()
                .map(c -> new ChangeView(c.getRevision(), c.getEffectivePeriodKey(), c.getClosingDay(),
                        c.getPreviousClosingDay(), c.getReason(), c.getActorUserId(), c.getCreatedAt()))
                .toList();
        return new ClosingSettingView(fn.apply(todayKey), revision, minEffective(organizationId, changes).toString(), views);
    }

    /** 今日を含む期間キーと、確定済み期間の最大キーの次、の遅い方。 */
    private YearMonth minEffective(Long organizationId, List<ShiftBudgetClosingDayChangeEntity> changes) {
        Function<YearMonth, Integer> fn = ShiftBudgetClosePeriodResolver.closingDayFunction(changes);
        YearMonth todayKey = ShiftBudgetClosePeriodCalculator.periodKeyOf(dateProvider.today(), fn);
        YearMonth maxFixed = periodResolver.maxFixedKey(organizationId);
        if (maxFixed == null) {
            return todayKey;
        }
        YearMonth afterClosed = maxFixed.plusMonths(1);
        return afterClosed.isAfter(todayKey) ? afterClosed : todayKey;
    }
}
