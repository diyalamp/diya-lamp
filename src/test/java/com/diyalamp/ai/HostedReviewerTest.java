package com.diyalamp.ai;

import com.diyalamp.workflow.*;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class HostedReviewerTest {
    private static JsonObject response(String text,String reason) {
        JsonObject r=new JsonObject(); r.addProperty("stop_reason",reason);
        JsonObject b=new JsonObject(); b.addProperty("type","text"); b.addProperty("text",text);
        JsonArray a=new JsonArray(); a.add(b); r.add("content",a); return r;
    }
    public static void main(String[] args) throws Exception {
        ReviewSession s=new ReviewSession("Synthetic draft, not private data."); AtomicInteger calls=new AtomicInteger();
        HostedReviewer reviewer=new HostedReviewer("test-model",request->{
            calls.incrementAndGet();
            if(request.has("tools") || request.get("max_tokens").getAsInt()!=4096) throw new AssertionError();
            JsonObject supplied=JsonParser.parseString(request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
            if(!supplied.get("draft").getAsString().equals(s.draft())) throw new AssertionError();
            return response("{\"phase\":\"ARGUMENT_MAP\",\"output\":\"Claim lacks support\",\"questions\":[\"What evidence supports it?\"]}","end_turn");
        });
        PhaseResult result=reviewer.review(s); check(s.state()==ReviewSession.State.ROUGH_DRAFT); s.submit(result); check(s.state()==ReviewSession.State.NEEDS_INPUT);
        try { reviewer.review(s); throw new AssertionError(); } catch(IllegalStateException expected) { }
        check(calls.get()==1);
        s.respond("Supplied synthetic evidence.");
        String valid="{\"phase\":\"ARGUMENT_MAP\",\"output\":\"Reviewed\",\"questions\":[]}";
        for(String invalid:List.of("not JSON","{\"phase\":\"FINAL_STRUCTURE\",\"output\":\"Skip\",\"questions\":[]}","{\"phase\":\"ARGUMENT_MAP\",\"output\":4,\"questions\":[]}","{\"phase\":\"ARGUMENT_MAP\",\"output\":\"\",\"questions\":[]}")) {
            reject(new HostedReviewer("test-model",r->response(invalid,"end_turn")),s);
        }
        reject(new HostedReviewer("test-model",r->response(valid,"max_tokens")),s);
        reject(new HostedReviewer("test-model",r->response(valid,"refusal")),s);
        reject(new HostedReviewer("test-model",r->{throw new IOException("Offline error");}),s);
        check(s.results().isEmpty());
        s.submit(new HostedReviewer("test-model",r->response(valid,"end_turn")).review(s)); check(s.results().size()==1);
        System.out.println("PASS: structured AI result, evidence gate, no calls while blocked, wrong phase, malformed/refused/truncated output, and no mutation on errors.");
    }
    private static void reject(HostedReviewer reviewer,ReviewSession s) throws Exception { try { reviewer.review(s); throw new AssertionError(); } catch(IOException expected) { } }
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
}
