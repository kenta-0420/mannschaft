package com.mannschaft.app.common.ranchsource;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminFacade;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminProvider;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryAck;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxHealthSummary;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 非TXの有限aggregate。未実装/取得失敗を健康な四源ゼロへ変換しない。 */
@Service
@RequiredArgsConstructor
public class SourceOutboxAdminService implements SourceOutboxAdminFacade {
    private final List<SourceOutboxAdminProvider> providers;
    private final Clock clock;
    private EnumMap<RanchRewardSourceType,SourceOutboxAdminProvider> registered() {
        var result=new EnumMap<RanchRewardSourceType,SourceOutboxAdminProvider>(RanchRewardSourceType.class);
        for(var provider:providers) if(provider.sourceType()==null || result.put(provider.sourceType(),provider)!=null) throw unavailable();
        return result;
    }
    @Override public SourceOutboxHealthSummary health() {
        var registered=registered();
        if(registered.size()!=RanchRewardSourceType.values().length) throw unavailable();
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            return new SourceOutboxHealthSummary(registered.values().stream().map(provider -> provider.health(now)).toList(),now);
        } catch(DataAccessException failure) { throw unavailable(); }
    }
    @Override public SourceOutboxAdminRetryAck retry(Long actor,RanchRewardSourceType type,UUID event,UUID key,SourceOutboxAdminRetryRequest request) {
        if(actor==null || actor<=0 || type==null || event==null || key==null || request==null) throw new IllegalArgumentException("源管理命令の定義が不正です");
        var provider=registered().get(type);if(provider==null) throw unavailable();
        try { return provider.retry(actor,event,key,request,clock.instant().truncatedTo(ChronoUnit.MICROS)); }
        catch(DataAccessException failure) { throw unavailable(); }
    }
    private static BusinessException unavailable() { return new BusinessException(SourceOutboxErrorCode.SOURCEOUTBOX_001); }
}
