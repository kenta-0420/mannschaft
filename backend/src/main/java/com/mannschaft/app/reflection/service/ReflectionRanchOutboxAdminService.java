package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.reflection.repository.ReflectionRanchOutboxAdminRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.ranchsource.SourceOutboxErrorCode;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminProvider;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryAck;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxHealthRow;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** reflectionだけの独立TX。入口のfresh SYSTEM_ADMIN/ACTIVE lock配下で呼ぶ。 */
@Service
@RequiredArgsConstructor
public class ReflectionRanchOutboxAdminService implements SourceOutboxAdminProvider {
    private final ReflectionRanchOutboxAdminRepository commands;
    private final ObjectMapper mapper;
    @Override public RanchRewardSourceType sourceType() { return RanchRewardSourceType.PERSONAL_RECALL_COMPLETE; }
    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    public SourceOutboxHealthRow health(Instant now) {
        var row=commands.health();
        return new SourceOutboxHealthRow(sourceType(),Long.toString(row.pending()),Long.toString(row.dead()),
                row.oldestAgeSeconds()==null?null:Math.max(0,row.oldestAgeSeconds()));
    }
    @Override
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    public SourceOutboxAdminRetryAck retry(Long actor,UUID event,UUID key,SourceOutboxAdminRetryRequest request,Instant now) {
        byte[] hash=hash(event,request.reasonCode());
        var saved=commands.command(actor,key);
        if(saved!=null) {
            if(!MessageDigest.isEqual(hash,saved.hash())) throw new BusinessException(SourceOutboxErrorCode.SOURCEOUTBOX_003);
            try {
                var ack=mapper.readValue(saved.result(),SourceOutboxAdminRetryAck.class);
                if(ack==null || !event.equals(ack.eventId()) || ack.sourceType()!=sourceType()) throw new BusinessException(SourceOutboxErrorCode.SOURCEOUTBOX_001);
                return ack;
            } catch(JsonProcessingException | IllegalArgumentException failure) { throw new BusinessException(SourceOutboxErrorCode.SOURCEOUTBOX_001); }
        }
        var current=commands.current(event);
        if(current==null) throw new BusinessException(SourceOutboxErrorCode.SOURCEOUTBOX_002);
        if("LEASED".equals(current.status()) && current.activeLease())
            throw new BusinessException(SourceOutboxErrorCode.SOURCEOUTBOX_004);
        var disposition="ACKED".equals(current.status())?SourceOutboxAdminRetryAck.Disposition.ALREADY_TERMINAL
                :SourceOutboxAdminRetryAck.Disposition.RETRY_SCHEDULED;
        if(disposition==SourceOutboxAdminRetryAck.Disposition.RETRY_SCHEDULED) commands.requeue(event,now);
        var ack=new SourceOutboxAdminRetryAck(UuidV7.generate(),sourceType(),event,disposition,now);
        try { commands.save(ack.commandId(),actor,key,hash,mapper.writeValueAsString(ack),now); }
        catch(JsonProcessingException failure) { throw new BusinessException(SourceOutboxErrorCode.SOURCEOUTBOX_001); }
        return ack;
    }
    private byte[] hash(UUID event,String reason) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:new String[]{"source-admin-retry-v1",sourceType().name(),event.toString(),reason}) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
            return digest.digest();
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException("管理命令比較を初期化できません"); }
    }
}
