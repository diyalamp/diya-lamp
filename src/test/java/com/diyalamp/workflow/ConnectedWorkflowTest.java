package com.diyalamp.workflow;

import com.diyalamp.ai.HostedReviewer;
import com.diyalamp.notion.*;
import com.diyalamp.local.SessionLock;
import com.diyalamp.report.ReviewReport;
import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

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
            HostedReviewer reviewer=new HostedReviewer("test-model",request->{
                JsonObject input=JsonParser.parseString(request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                JsonObject result=new JsonObject(); result.addProperty("phase",input.get("phase").getAsString()); result.addProperty("output","Review artifact"); result.add("questions",new JsonArray());
                JsonObject text=new JsonObject(); text.addProperty("type","text"); text.addProperty("text",result.toString()); JsonArray content=new JsonArray(); content.add(text);
                JsonObject response=new JsonObject(); response.addProperty("stop_reason","end_turn"); response.add("content",content); return response;
            });
            for(Phase p:Phase.values()) {
                s.submit(reviewer.review(s)); SessionStore.save(session,new SessionStore.Saved(source,s)); s=SessionStore.load(session).review();
            }
            check(s.state()==ReviewSession.State.AWAITING_APPROVAL);
            new NotionPublisher.Source(page,draft.revision(),draft.fingerprint()).verify(reader.read(page),s);
            s.approve(Files.readString(source)); check(ReviewReport.render(s).contains("State: READY"));
            NotionPublisher publisher=new NotionPublisher(reader,(path,request)-> {
                check(request.getAsJsonArray("children").size()>0); JsonObject response=new JsonObject(); response.addProperty("id",block); return response;
            });
            check(publisher.publish(new NotionPublisher.Source(page,draft.revision(),draft.fingerprint()),s,page,"0123456789abcdef").equals(block));
            System.out.println("PASS: full offline Notion import, five hosted-review contracts, restart, explicit approval, report, publication, and concurrent lock.");
        } finally { try(var paths=Files.walk(dir)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); } }
    }
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
}
