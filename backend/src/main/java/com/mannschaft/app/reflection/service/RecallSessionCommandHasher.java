package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.RecallSessionCommandType;
import com.mannschaft.app.reflection.dto.RecallSessionAnswer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** ACK 再送専用の私有比較値。報酬の完全一致 HMAC に流用しない。 */
@Component
@RequiredArgsConstructor
public class RecallSessionCommandHasher {
    private final ObjectMapper mapper;

    /** 源の purge 対象となる BINARY32 だけを返す。原文を返却・ログ出力しない。 */
    public byte[] hash(long userId, RecallSessionCommandType kind, UUID resourceId, long version,
            List<RecallSessionAnswer> answers, RecallSelfRating selfRating) {
        if(userId<=0 || kind==null || resourceId==null || version<0 || answers==null) throw invalid();
        if(kind!=RecallSessionCommandType.COMPLETE && selfRating!=null) throw invalid();
        if(kind==RecallSessionCommandType.COMPLETE && selfRating==null) throw invalid();
        if((kind==RecallSessionCommandType.START || kind==RecallSessionCommandType.CANCEL) && !answers.isEmpty())
            throw invalid();
        if(kind==RecallSessionCommandType.START && version!=0) throw invalid();
        var ids=new HashSet<UUID>();
        for(var answer:answers)
            if(answer==null || answer.promptId()==null || answer.state()==null || !ids.add(answer.promptId())) throw invalid();
        var root=mapper.createObjectNode();
        root.put("purpose","recall-session:command:v1");root.put("user",Long.toString(userId));
        root.put("kind",kind.name());root.put("resourceId",resourceId.toString());
        root.put("version",Long.toString(version));
        String base="/api/v1/me/reflections/";
        String path=switch(kind){
            case START -> base+"entries/"+resourceId+"/recall-sessions";
            case ANSWERS -> base+"recall-sessions/"+resourceId+"/answers";
            case COMPLETE -> base+"recall-sessions/"+resourceId+"/complete";
            case CANCEL -> base+"recall-sessions/"+resourceId+"/cancel";
        };
        root.put("path",path);root.put("method",kind==RecallSessionCommandType.ANSWERS?"PUT":"POST");
        var array=root.putArray("answers");
        for(var answer:answers.stream().sorted(Comparator.comparing(a->a.promptId().toString())).toList()) {
            var row=array.addObject();row.put("promptId",answer.promptId().toString());row.put("state",answer.state().name());
            if(answer.text()==null)row.putNull("text");else row.put("text",answer.text());
        }
        if(selfRating==null)root.putNull("selfRating");else root.put("selfRating",selfRating.name());
        try{return MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(root));}
        catch(Exception ignored){throw invalid();}
    }

    private static BusinessException invalid(){return new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);}
}
