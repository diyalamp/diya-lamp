package com.diyalamp.ai;

import com.diyalamp.workflow.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class CodexReviewerTest {
    public static void main(String[] args) throws Exception {
        ReviewSession s=new ReviewSession("Synthetic draft, not private data."); AtomicInteger calls=new AtomicInteger();
        CodexReviewer reviewer=new CodexReviewer(null,(prompt,schema,model)->{
            calls.incrementAndGet();
            if(model!=null || !schema.contains("ARGUMENT_MAP") || !prompt.contains(s.draft())) throw new AssertionError();
            return "{\"phase\":\"ARGUMENT_MAP\",\"output\":\"Claim lacks support\",\"questions\":[\"What evidence supports it?\"]}";
        });
        PhaseResult result=reviewer.review(s); check(s.state()==ReviewSession.State.ROUGH_DRAFT); s.submit(result); check(s.state()==ReviewSession.State.NEEDS_INPUT);
        try { reviewer.review(s); throw new AssertionError(); } catch(IllegalStateException expected) { }
        check(calls.get()==1); s.respond("Supplied synthetic evidence.");
        String valid="{\"phase\":\"ARGUMENT_MAP\",\"output\":\"Reviewed\",\"questions\":[]}";
        for(String invalid:List.of("not JSON","{\"phase\":\"FINAL_STRUCTURE\",\"output\":\"Skip\",\"questions\":[]}","{\"phase\":\"ARGUMENT_MAP\",\"output\":4,\"questions\":[]}","{\"phase\":\"ARGUMENT_MAP\",\"output\":\"\",\"questions\":[]}")) {
            reject(new CodexReviewer("test-model",(p,j,m)->invalid),s);
        }
        for (String malformed : List.of(
                valid.replace("\"phase\":", "\"phase\":\"ARGUMENT_MAP\",\"phase\":"),
                "/* comment */" + valid,
                valid.replace("\"output\":\"Reviewed\"", "output:'Reviewed'"))) {
            reject(new CodexReviewer(null, (p,j,m) -> malformed), s);
        }
        reject(new CodexReviewer(null,(p,j,m)->{throw new IOException("Offline error");}),s);
        check(s.results().isEmpty()); s.submit(new CodexReviewer(null,(p,j,m)->valid).review(s)); check(s.results().size()==1);
        System.out.println("PASS: Codex structured result, evidence gate, default model, malformed/wrong phase output, and no mutation on errors.");
    }
    private static void reject(CodexReviewer reviewer,ReviewSession s) throws Exception { try { reviewer.review(s); throw new AssertionError(); } catch(IOException expected) { } }
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
}
