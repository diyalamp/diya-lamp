package com.diyalamp.ai;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class DocumentReviewAgentTest {
    public static void main(String[] args) throws Exception {
        String draft = "🪔 Price caused the decline.";
        var snapshot = DocumentReviewAgent.Snapshot.of(draft);
        var evidence = List.of(new DocumentReviewAgent.Evidence("interviews", "Three synthetic interviews mentioned price."));
        JsonObject valid = result(snapshot.fingerprint());
        AtomicInteger calls = new AtomicInteger();
        var agent = new DocumentReviewAgent(null, (prompt,schema,model)->{
            calls.incrementAndGet(); check(prompt.contains(draft) && prompt.contains("untrusted data") && schema.contains(snapshot.fingerprint()));
            return valid.toString();
        });
        var review = agent.review(snapshot, evidence); check(review.findings().size() == 1); review.requireCurrent(draft);
        check(review.findings().get(0).start() == 3 && review.findings().get(0).passage().equals("Price caused the decline."));
        try { review.requireCurrent(draft + " New evidence."); throw new AssertionError(); } catch (IllegalStateException expected) { }
        for (String field : List.of("start", "end", "passage", "evidence_ids", "evidence_kind", "proposed_text")) {
            JsonObject changed = valid.deepCopy(); JsonObject f = changed.getAsJsonArray("findings").get(0).getAsJsonObject();
            switch (field) {
                case "start" -> f.addProperty(field, 1); // Splits the lamp's surrogate pair.
                case "end" -> f.addProperty(field, 999);
                case "passage" -> f.addProperty(field, "Invented wording");
                case "evidence_ids" -> { JsonArray ids = new JsonArray(); ids.add("invented-source"); f.add(field,ids); }
                case "evidence_kind" -> f.addProperty(field,"VERIFIED_TRUTH");
                case "proposed_text" -> f.addProperty(field,"");
            }
            reject(changed.toString(),snapshot,evidence);
        }
        JsonObject stale = valid.deepCopy(); stale.addProperty("fingerprint", "0".repeat(64)); reject(stale.toString(),snapshot,evidence);
        JsonObject unknown = valid.deepCopy(); unknown.addProperty("approved",true); reject(unknown.toString(),snapshot,evidence);
        JsonObject fractional = valid.deepCopy(); fractional.getAsJsonArray("findings").get(0).getAsJsonObject().addProperty("start",3.5); reject(fractional.toString(),snapshot,evidence);
        JsonObject excess = valid.deepCopy(); for(int i=0;i<3;i++) excess.getAsJsonArray("findings").add(excess.getAsJsonArray("findings").get(0).deepCopy()); reject(excess.toString(),snapshot,evidence);
        JsonObject duplicate = valid.deepCopy(); duplicate.getAsJsonArray("findings").add(duplicate.getAsJsonArray("findings").get(0).deepCopy()); reject(duplicate.toString(),snapshot,evidence);
        reject("not JSON",snapshot,evidence);
        reject(valid.toString().replace("\"fingerprint\":", "\"fingerprint\":\"" + snapshot.fingerprint() + "\",\"fingerprint\":"),snapshot,evidence);
        JsonObject empty = valid.deepCopy(); empty.add("findings",new JsonArray()); check(new DocumentReviewAgent(null,(p,s,m)->empty.toString()).review(snapshot,evidence).findings().isEmpty());
        try { agent.review(snapshot,List.of(evidence.get(0),evidence.get(0))); throw new AssertionError(); } catch(IllegalArgumentException expected) { }
        try { DocumentReviewAgent.Snapshot.of("Invalid " + (char)0xD800); throw new AssertionError(); } catch(IllegalArgumentException expected) { }
        check(calls.get()==1); check(snapshot.draft().equals(draft));
        System.out.println("PASS: anchored document proposals, stale fingerprint rejection, UTF-16 boundaries, unknown evidence, bounded findings, strict types and no mutation.");
    }
    private static JsonObject result(String fingerprint) {
        JsonObject result = new JsonObject(); result.addProperty("fingerprint",fingerprint); result.add("alternative",JsonNull.INSTANCE); result.addProperty("limitations","Only supplied excerpts were examined; causation is not established.");
        JsonObject f = new JsonObject(); f.addProperty("id","cause"); f.addProperty("start",3); f.addProperty("end",28); f.addProperty("passage","Price caused the decline.");
        f.addProperty("issue","Interviews do not establish causation."); f.addProperty("consequence","The price reduction relies on this assumption."); f.addProperty("proposed_text","Pricing may contribute to the decline."); f.addProperty("evidence_kind","SUPPLIED_SOURCE");
        JsonArray ids = new JsonArray(); ids.add("interviews"); f.add("evidence_ids",ids); JsonArray findings = new JsonArray(); findings.add(f); result.add("findings",findings); return result;
    }
    private static void reject(String raw,DocumentReviewAgent.Snapshot snapshot,List<DocumentReviewAgent.Evidence> evidence) throws Exception {
        try { new DocumentReviewAgent(null,(p,s,m)->raw).review(snapshot,evidence); throw new AssertionError("Accepted invalid review"); } catch(IOException expected) { }
    }
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
}
