package com.mannschaft.app.skill.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.skill.SkillStatus;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 資格の失効ステータス更新バッチ（ACTIVE → EXPIRED）。
 *
 * <h2>なぜリマインダーと別クラスなのか（Gate 基盤工事④-B 第三陣）</h2>
 * <p>元は {@link SkillExpiryReminderBatchService#runReminder()} の中で
 * リマインダー送信と一緒に行われていたが、両者は<b>止めてよいかの判定が正反対</b>である。</p>
 * <ul>
 *   <li><b>リマインダー送信</b>— 資格・履歴書機能が閉じている間に通知を送る意味は無い。
 *       送らなくても既存データは壊れない。よって
 *       {@code SKIP_WHEN_DISABLED}（{@code FEATURE_SKILL_RESUME_ENABLED}）。</li>
 *   <li><b>失効ステータス更新（本クラス）</b>— 止めると、有効期限を過ぎた資格が
 *       {@code ACTIVE} のまま残る。これは<b>既存データの整合性が壊れる</b>側であり、
 *       期限切れの資格を「有効」と表示し続けることになる。よって {@code ALWAYS}。</li>
 * </ul>
 *
 * <p><b>クラスを分けた理由</b>: 番人 {@code BackgroundEntryPolicyDeclarationGuardTest} の
 * 禁止域 {@code FORBIDDEN_TO_STOP} は<b>クラス単位</b>で照合する。
 * 1 クラスの中に {@code SKIP} と {@code ALWAYS} が同居していると、
 * そのクラスを禁止域に登録できない（登録すると {@code SKIP} 側が違反になる）。
 * すなわち<b>「止めてはならぬ」側を番人で守れない</b>。
 * 別クラスに切り出すことで本クラスを禁止域へ登録でき、
 * 将来誰かが {@code SKIP_WHEN_DISABLED} へ書き換えても CI が止める。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillExpiryStatusUpdateBatchService {

    private final MemberSkillRepository memberSkillRepository;

    /**
     * 有効期限が過ぎた ACTIVE 資格を EXPIRED に更新する。毎日 08:05（JST）。
     *
     * <p>リマインダー（08:00）より後に置く。元の単一メソッドでも
     * 「リマインダー → 失効更新」の順であり、その順序を保つ。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると有効期限を過ぎた資格が ACTIVE のまま残り、失効済みの資格を有効と表示し続ける。これは既存データの整合性が壊れる側であり、機能フラグの状態に関わらず必ず実行する")
    @BatchEndpoint(name = "skill-expiry-status-update-daily",
            description = "有効期限を過ぎた ACTIVE 資格を毎日 08:05 に EXPIRED へ更新する")
    @Scheduled(cron = "0 5 8 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "skill_expiry_status_update", lockAtMostFor = "PT10M")
    @Transactional
    public void runExpiry() {
        int count = updateExpiredSkills();
        log.info("資格失効ステータス更新バッチ完了: expired={}", count);
    }

    /**
     * 有効期限が過ぎた ACTIVE 資格を EXPIRED に更新する。
     *
     * @return 更新件数
     */
    public int updateExpiredSkills() {
        LocalDate today = LocalDate.now();
        List<MemberSkillEntity> expiredSkills =
                memberSkillRepository.findByExpiresAtBeforeAndStatusAndDeletedAtIsNull(
                        today, SkillStatus.ACTIVE);

        int count = 0;
        for (MemberSkillEntity skill : expiredSkills) {
            try {
                skill.expire();
                memberSkillRepository.save(skill);
                count++;
            } catch (Exception e) {
                log.warn("資格期限切れ更新失敗: memberSkillId={}", skill.getId(), e);
            }
        }
        return count;
    }
}
