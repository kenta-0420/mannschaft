package com.mannschaft.app.common.activityschedule;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** 予定完了と活動表示を同一短TXで段取りする契約。試練先行の骨格。 */
@Service
public class AutomaticScheduleCompletionFacade {
    @Transactional
    public boolean completeOne(Long id, OffsetDateTime now) { return false; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reopenFuture(List<Long> ids, OffsetDateTime now) { }
}
