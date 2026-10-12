package com.diyalamp.ai;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static com.diyalamp.ai.DocumentReviewAgent.*;

/** Fictional, offline contract tests. These do not measure a model's semantic accuracy. */
public final class GroundedDocumentReviewTest {
    private static final String DRAFT = "Price caused the decline.";
    private static final Snapshot SNAPSHOT = Snapshot.of(DRAFT);
    private static final List<Evidence> SOURCES = List.of(
            new Evidence("survey", "🪔 Three people mentioned price. The sample was self-selected."),
            new Evidence("other", "Traffic also declined."));

    public static void main(String[] args) throws Exception {
        JsonObject valid = valid();
        AtomicInteger calls = new AtomicInteger();
        GroundedResult result = new DocumentReviewAgent(null, (prompt, schema, model) -> {
            calls.incrementAndGet();
            check(prompt.contains("source_passages") && prompt.contains("not proof of support"), "grounding instructions");
            JsonObject item = JsonParser.parseString(schema).getAsJsonObject().getAsJsonObject("properties")
                    .getAsJsonObject("findings").getAsJsonObject("items");
            check(item.getAsJsonArray("required").contains(new JsonPrimitive("source_passages")), "required citation schema");
            check(!item.getAsJsonObject("properties").getAsJsonObject("source_passages")
                    .getAsJsonObject("items").get("additionalProperties").getAsBoolean(), "closed citation schema");
            return valid.toString();
        }).reviewGrounded(SNAPSHOT, SOURCES);
        check(calls.get() == 1, "one bounded provider call");
        check(result.passagesByFinding().get("causation").equals(List.of(new SourcePassage("survey", 3, 32, "Three people mentioned price."))), "exact UTF-16 citation");
        result.requireCurrent(DRAFT, SOURCES);
        result.requireCurrent(DRAFT, List.of(SOURCES.get(1), SOURCES.get(0)));
        stale(() -> result.requireCurrent(DRAFT + "!", SOURCES));
        stale(() -> result.requireCurrent(DRAFT, List.of(new Evidence("survey", SOURCES.get(0).excerpt() + " Updated."), SOURCES.get(1))));
        stale(() -> result.requireCurrent(DRAFT, List.of(SOURCES.get(0), new Evidence("other", "Uncited input changed."))));
        stale(() -> result.requireCurrent(DRAFT, List.of(SOURCES.get(0))));
        stale(() -> result.requireCurrent(DRAFT, List.of(SOURCES.get(0), SOURCES.get(1), new Evidence("new", "New context."))));
        stale(() -> result.requireCurrent(DRAFT, List.of(SOURCES.get(0), SOURCES.get(0))));
        immutable(() -> result.passagesByFinding().clear());
        immutable(() -> result.passagesByFinding().get("causation").clear());

        rejectCitation(p -> p.addProperty("source_id", "invented"));
        rejectCitation(p -> p.addProperty("source_id", "other"));
        rejectCitation(p -> p.addProperty("start", 4));
        rejectCitation(p -> p.addProperty("start", -1));
        rejectCitation(p -> p.addProperty("start", "3"));
        rejectCitation(p -> p.addProperty("start", 3.5));
        rejectCitation(p -> p.addProperty("end", 500));
        rejectCitation(p -> p.addProperty("end", 3));
        rejectCitation(p -> p.addProperty("quote", "Three people proved price caused the decline."));
        rejectCitation(p -> p.addProperty("quote", ""));
        rejectCitation(p -> p.addProperty("quote", " "));
        rejectCitation(p -> p.add("quote", JsonNull.INSTANCE));
        rejectCitation(p -> p.addProperty("verified", true));
        rejectCitation(p -> p.remove("quote"));
        JsonObject split = valid();
        JsonObject citation = firstCitation(split); citation.addProperty("start", 1); citation.addProperty("end", 2); citation.addProperty("quote", String.valueOf((char)0xDE94));
        reject(split);
        JsonObject missing = valid(); finding(missing).remove("source_passages"); reject(missing);
        JsonObject noPassages = valid(); finding(noPassages).add("source_passages", new JsonArray()); reject(noPassages);
        JsonObject duplicate = valid(); passages(duplicate).add(firstCitation(duplicate).deepCopy()); reject(duplicate);
        JsonObject excessive = valid(); for (int i = 0; i < 10; i++) passages(excessive).add(firstCitation(excessive).deepCopy()); reject(excessive);
        JsonObject unmatchedId = valid(); finding(unmatchedId).getAsJsonArray("evidence_ids").add("other"); reject(unmatchedId);
        JsonObject extraPassage = valid(); passages(extraPassage).add(passage("other", 0, 22, "Traffic also declined.")); reject(extraPassage);
        JsonObject nonSourceWithPassage = valid(); finding(nonSourceWithPassage).addProperty("evidence_kind", "INFERENCE"); finding(nonSourceWithPassage).add("evidence_ids", new JsonArray()); reject(nonSourceWithPassage);
        JsonObject nonSource = nonSourceWithPassage.deepCopy(); finding(nonSource).add("source_passages", new JsonArray());
        check(run(nonSource).passagesByFinding().get("causation").isEmpty(), "non-source finding is explicit, not invented citation");
        JsonObject empty = valid(); empty.add("findings", new JsonArray());
        check(run(empty).passagesByFinding().isEmpty(), "abstention remains allowed");
        JsonObject multiple = valid(); finding(multiple).getAsJsonArray("evidence_ids").add("other"); passages(multiple).add(passage("other", 0, 22, "Traffic also declined."));
        check(run(multiple).passagesByFinding().get("causation").size() == 2, "multiple source passages");
        JsonObject supplementary = valid(); JsonObject lamp = firstCitation(supplementary); lamp.addProperty("start", 0); lamp.addProperty("end", 2); lamp.addProperty("quote", "🪔");
        check(run(supplementary).passagesByFinding().get("causation").get(0).quote().equals("🪔"), "valid supplementary character accepted without claiming semantic support");
        // The older contract intentionally does not accept new unrecognized fields.
        try { new DocumentReviewAgent(null, (p,s,m) -> valid.toString()).review(SNAPSHOT, SOURCES); throw new AssertionError("legacy silently accepted new fields"); }
        catch (IOException expected) { }
        System.out.println("PASS: exact source passages, complete source-set freshness, immutable results, malformed citations and legacy contract isolation.");
    }
    private static JsonObject valid() {
        JsonObject result = new JsonObject(); result.addProperty("fingerprint", SNAPSHOT.fingerprint()); result.add("alternative", JsonNull.INSTANCE);
        result.addProperty("limitations", "Supplied excerpts are unverified; exact quotes do not prove the finding.");
        JsonObject f = new JsonObject(); f.addProperty("id", "causation"); f.addProperty("start", 0); f.addProperty("end", DRAFT.length()); f.addProperty("passage", DRAFT);
        f.addProperty("issue", "A small self-selected survey does not isolate the cause."); f.addProperty("consequence", "A price cut could fail to address the decline."); f.add("proposed_text", JsonNull.INSTANCE);
        f.addProperty("evidence_kind", "SUPPLIED_SOURCE"); JsonArray ids = new JsonArray(); ids.add("survey"); f.add("evidence_ids", ids);
        JsonArray passages = new JsonArray(); passages.add(passage("survey", 3, 32, "Three people mentioned price.")); f.add("source_passages", passages);
        JsonArray findings = new JsonArray(); findings.add(f); result.add("findings", findings); return result;
    }
    private static JsonObject passage(String id, int start, int end, String quote) {
        JsonObject p = new JsonObject(); p.addProperty("source_id", id); p.addProperty("start", start); p.addProperty("end", end); p.addProperty("quote", quote); return p;
    }
    private static JsonObject finding(JsonObject r) { return r.getAsJsonArray("findings").get(0).getAsJsonObject(); }
    private static JsonArray passages(JsonObject r) { return finding(r).getAsJsonArray("source_passages"); }
    private static JsonObject firstCitation(JsonObject r) { return passages(r).get(0).getAsJsonObject(); }
    private static GroundedResult run(JsonObject r) throws Exception { return new DocumentReviewAgent(null, (p,s,m) -> r.toString()).reviewGrounded(SNAPSHOT, SOURCES); }
    private static void rejectCitation(Consumer<JsonObject> edit) throws Exception { JsonObject r = valid(); edit.accept(firstCitation(r)); reject(r); }
    private static void reject(JsonObject r) throws Exception { try { run(r); throw new AssertionError("Accepted invalid source citation"); } catch (IOException expected) { } }
    private static void stale(Runnable action) { try { action.run(); throw new AssertionError("Accepted stale sources"); } catch (IllegalStateException | IllegalArgumentException expected) { } }
    private static void immutable(Runnable action) { try { action.run(); throw new AssertionError("Mutable result"); } catch (UnsupportedOperationException expected) { } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
