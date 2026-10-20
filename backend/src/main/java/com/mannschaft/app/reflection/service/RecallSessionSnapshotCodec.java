package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.RecallSessionErrorCode;
import com.mannschaft.app.reflection.dto.RecallSessionAnswer;
import com.mannschaft.app.reflection.dto.RecallSessionPrompt;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.dto.RecallSessionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/** 保存 snapshot だけを復元する。解析原因や学習本文を例外へ添付しない。 */
@Component
@RequiredArgsConstructor
public class RecallSessionSnapshotCodec {
    private final ObjectMapper objectMapper;

    public String promptsJson(List<RecallSessionPrompt> prompts) {
        if(prompts==null || prompts.isEmpty() || prompts.size()>1501) throw invalid();
        return json(List.copyOf(prompts));
    }

    public List<RecallSessionPrompt> prompts(String encoded) {
        try {
            List<RecallSessionPrompt> result=objectMapper.readValue(encoded,new TypeReference<>(){});
            if(result.isEmpty() || result.size()>1501) throw invalid();
            return List.copyOf(result);
        } catch(Exception ignored){throw invalid();}
    }

    public String answersJson(List<RecallSessionAnswer> answers){return json(List.copyOf(answers));}

    public List<RecallSessionAnswer> answers(String encoded) {
        try{return List.copyOf(objectMapper.readValue(encoded,new TypeReference<List<RecallSessionAnswer>>(){}));}
        catch(Exception ignored){throw invalid();}
    }

    public String originalJson(ReflectionEntryResponse original) {
        if(original==null || original.isMasked() || original.structuredContent()==null) throw invalid();
        return json(original);
    }

    public ReflectionEntryResponse original(String encoded) {
        try {
            ReflectionEntryResponse result=objectMapper.readValue(encoded,ReflectionEntryResponse.class);
            if(result==null || result.isMasked() || result.structuredContent()==null) throw invalid();
            return result;
        }catch(Exception ignored){throw invalid();}
    }

    /** 成功時の応答をそのまま保存し、再送時に現在の本文から作り直さない。 */
    public String responseJson(RecallSessionResponse response) {
        if(response==null) throw invalid();
        return json(response);
    }

    /** 保存済み応答の破損は入力エラーへ変換せず、固定の利用不能分類にする。 */
    public RecallSessionResponse response(String encoded) {
        try {
            RecallSessionResponse result=objectMapper.readValue(encoded,RecallSessionResponse.class);
            if(result==null) throw invalid();
            return result;
        }catch(Exception ignored){throw invalid();}
    }

    private String json(Object value) {
        try{return objectMapper.writeValueAsString(value);}
        catch(Exception ignored){throw invalid();}
    }

    private static BusinessException invalid(){return new BusinessException(RecallSessionErrorCode.UNAVAILABLE);}
}
