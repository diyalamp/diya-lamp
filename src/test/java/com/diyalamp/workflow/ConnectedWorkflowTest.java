package com.diyalamp.workflow;

import com.diyalamp.ai.CodexReviewer;
import com.diyalamp.notion.*;
import com.diyalamp.local.SessionLock;
import com.diyalamp.report.ReviewReport;
import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Full offline path through both network contracts and saved sessions. */
public final class ConnectedWorkflowTest {
    public static void main(String[] args) throws Exception {
        String page="00000000000000000000000000000001", block="00000000000000000000000000000002";
        NotionClient reader=new NotionClient(path->{
            if(path.startsWith("pages/")) return JsonParser.parseString("{\"last_edited_time\":\"a\"}").getAsJsonObject();
            return JsonParser.parseString("{\"results\":[{\"id\":\""+block+"\",\"type\":\"paragraph\",\"paragraph\":{\"rich_text\":[{\"plain_text\":\"Synthetic proposal\"}]}}],\"has_more\":false}").getAsJsonObject();
        });
        NotionClient.Draft draft=reader.read(page); ReviewSession s=new ReviewSession(draft.text());
        Path dir=Files.createTempDirectory("diyalamp-connected-"); Path source=dir.resolve("draft.txt"),session=dir.resolve("demo.review.properties"); Files.writeString(source,s.draft());
        try {
            try(SessionLock lock=new SessionLock(session)) {
                try { new SessionLock(session).close(); throw new AssertionError("Concurrent lock acquired"); } catch(IOException expected) { }
            }
            AtomicInteger calls=new AtomicInteger();
            CodexReviewer reviewer=new CodexReviewer("test-model",(prompt,schema,model)->{
                JsonObject input=JsonParser.parseString(prompt.substring(prompt.indexOf("Review input:\n")+"Review input:\n".length())).getAsJsonObject();
                int call=calls.incrementAndGet();
                JsonArray questions=new JsonArray();
                if(call<=3) {
                    check(input.get("phase").getAsString().equals("ARGUMENT_MAP"));
                    check(input.getAsJsonArray("completed_reviews").isEmpty());
                    if(call==1) {
                        check(!input.has("previous_phase_questions"));
                        questions.add("What evidence supports the claim?\nInclude the sample size.");
                        questions.add("Who collected it?");
                    } else {
                        JsonArray previous=input.getAsJsonArray("previous_phase_questions");
                        if(previous==null) throw new AssertionError("Re-review omitted outstanding evidence questions");
                        String context=input.getAsJsonObject("author_context").get("ARGUMENT_MAP").getAsString();
                        check(input.get("previous_phase_review").getAsString().equals("Review artifact"));
                        check(context.contains("Synthetic sample of 12."));
                        if(call==2) {
                            check(previous.size()==2);
                            check(previous.get(0).getAsString().equals("What evidence supports the claim?\nInclude the sample size."));
                            check(previous.get(1).getAsString().equals("Who collected it?"));
                            questions.add("Who collected it?");
                        } else {
                            check(previous.size()==1 && previous.get(0).getAsString().equals("Who collected it?"));
                            check(context.contains("Collected by the fictional research team."));
                        }
                    }
                } else {
                    check(!input.has("previous_phase_questions"));
                    check(!input.has("previous_phase_review"));
                    check(input.get("phase").getAsString().equals(Phase.values()[call-3].name()));
                }
                JsonObject result=new JsonObject(); result.addProperty("phase",input.get("phase").getAsString()); result.addProperty("output","Review artifact"); result.add("questions",questions);
                return result.toString();
            });
            for(String context:List.of("Synthetic sample of 12.","Collected by the fictional research team.")) {
                s.submit(reviewer.review(s));
                SessionStore.save(session,new SessionStore.Saved(source,s)); s=SessionStore.load(session).review();
                check(s.state()==ReviewSession.State.NEEDS_INPUT);
                int before=calls.get();
                try { reviewer.review(s); throw new AssertionError("Unanswered evidence request accepted"); }
                catch(IllegalStateException expected) { }
                check(calls.get()==before);
                s.respond(context);
                SessionStore.save(session,new SessionStore.Saved(source,s)); s=SessionStore.load(session).review();
                check(s.state()==ReviewSession.State.REVIEWING && s.results().isEmpty());
                try { s.approve(s.draft()); throw new AssertionError("Premature approval accepted"); }
                catch(IllegalStateException expected) { }
            }
            for(Phase p:Phase.values()) {
                check(s.nextPhase().orElseThrow()==p);
                s.submit(reviewer.review(s)); SessionStore.save(session,new SessionStore.Saved(source,s)); s=SessionStore.load(session).review();
            }
            check(calls.get()==7);
            check(s.state()==ReviewSession.State.AWAITING_APPROVAL);
            new NotionPublisher.Source(page,draft.revision(),draft.fingerprint()).verify(reader.read(page),s);
            s.approve(Files.readString(source)); check(ReviewReport.render(s).contains("State: READY"));
            NotionPublisher publisher=new NotionPublisher(reader,(path,request)-> {
                check(request.getAsJsonArray("children").size()>0); JsonObject response=new JsonObject(); response.addProperty("id",block); return response;
            });
            check(publisher.publish(new NotionPublisher.Source(page,draft.revision(),draft.fingerprint()),s,page,"0123456789abcdef").equals(block));
            System.out.println("PASS: full offline Notion import, five Codex-review contracts, repeated evidence gates across restarts, explicit approval, report, publication, and concurrent lock.");
        } finally { try(var paths=Files.walk(dir)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); } }
    }
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
}
