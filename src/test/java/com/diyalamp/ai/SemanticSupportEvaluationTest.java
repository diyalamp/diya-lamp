package com.diyalamp.ai;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import static com.diyalamp.ai.SemanticSupportEvaluation.*;

/** Oracle predictions below test scorer arithmetic, not model performance. */
public final class SemanticSupportEvaluationTest {
    public static void main(String[] args) throws Exception {
        List<Case> cases = readCases(Files.readString(Path.of("examples/evaluation/support-cases.json")));
        check(cases.size() == 12, "case count");
        for (Label label : List.of(Label.SUPPORTS, Label.CONTRADICTS, Label.INSUFFICIENT_CONTEXT))
            check(cases.stream().filter(c -> c.expected() == label).count() == 4, "balanced labels");
        check(cases.stream().map(Case::family).distinct().count() >= 8, "varied phenomena");
        List<Prediction> oracle = cases.stream().map(c -> new Prediction(c.id(), c.expected(), List.of())).toList();
        JsonObject perfect = score(cases, oracle);
        near(perfect,"accuracy_all_cases",1); near(perfect,"macro_f1_present_gold_labels",1);
        check(perfect.get("exact_citation_fraction").isJsonNull(), "missing citations are not perfect citations");
        List<Prediction> alwaysSupports = cases.stream().map(c -> new Prediction(c.id(), Label.SUPPORTS,
                List.of(new Citation(c.sources().get(0).id(), 0, c.sources().get(0).excerpt().length(), c.sources().get(0).excerpt())))).toList();
        JsonObject wrong = score(cases, alwaysSupports);
        near(wrong,"exact_citation_fraction",1); near(wrong,"accuracy_all_cases",1.0/3);
        check(wrong.get("unsupported_claims_marked_supported").getAsInt() == 8, "verbatim citations do not establish support");
        near(wrong,"macro_f1_present_gold_labels",1.0/6);
        JsonObject empty = score(cases, List.of());
        near(empty,"coverage",0); near(empty,"accuracy_all_cases",0);
        check(empty.get("missing_count").getAsInt() == 12 && empty.get("accuracy_answered").isJsonNull(), "missing outputs stay in denominator");
        JsonObject abstained = score(cases, cases.stream().map(c -> new Prediction(c.id(), Label.ABSTAIN, List.of())).toList());
        check(abstained.get("abstained_count").getAsInt() == 12 && abstained.get("missing_count").getAsInt() == 0, "abstention separate from missing");
        List<Prediction> partial = List.of(oracle.get(0)); JsonObject sparse = score(cases, partial);
        near(sparse,"accuracy_answered",1); near(sparse,"accuracy_all_cases",1.0/12); near(sparse,"coverage",1.0/12);
        Case one = cases.get(0);
        JsonObject badQuote = score(List.of(one), List.of(new Prediction(one.id(), one.expected(), List.of(new Citation("pilot",0,5,"invented")))));
        near(badQuote,"accuracy_all_cases",1); near(badQuote,"exact_citation_fraction",0);
        for (Citation bad : List.of(new Citation("missing",0,1,"T"),new Citation("pilot",-1,1,"T"),new Citation("pilot",0,99999,"T"),new Citation("pilot",0,0,"")))
            near(score(List.of(one),List.of(new Prediction(one.id(),one.expected(),List.of(bad)))),"exact_citation_fraction",0);
        Case emoji = new Case("unicode","scope","development","Lamp",List.of(new DocumentReviewAgent.Evidence("s","🪔 light")),Label.SUPPORTS,"Synthetic Unicode boundary test.");
        near(score(List.of(emoji),List.of(new Prediction("unicode",Label.SUPPORTS,List.of(new Citation("s",0,2,"🪔"))))),"exact_citation_fraction",1);
        near(score(List.of(emoji),List.of(new Prediction("unicode",Label.SUPPORTS,List.of(new Citation("s",1,2,String.valueOf((char)0xDE94)))))),"exact_citation_fraction",0);
        rejects(() -> score(cases,List.of(oracle.get(0),oracle.get(0))));
        rejects(() -> score(cases,List.of(new Prediction("unknown",Label.SUPPORTS,List.of()))));
        rejects(() -> score(List.of(one,one),List.of()));
        rejects(() -> new Prediction("x",Label.ABSTAIN,List.of(new Citation("s",0,1,"x"))));
        rejects(() -> new Prediction("x",Label.SUPPORTS,List.of(new Citation("s",0,1,"x"),new Citation("s",0,1,"x"))));
        var parsed = readPredictions("[{\"id\":\"randomized-effect\",\"label\":\"SUPPORTS\",\"citations\":[]}]");
        check(parsed.equals(partial), "prediction input contract");
        rejects(() -> readPredictions("[{\"id\":\"x\",\"label\":\"SUPPORTS\",\"citations\":[],\"label\":\"CONTRADICTS\"}]"));
        rejects(() -> readPredictions("[{\"id\":\"x\",\"label\":\"TRUE\",\"citations\":[]}]"));
        System.out.println("PASS: synthetic support evaluation arithmetic, missing/abstention accounting, citation/support separation and malformed input rejection.");
    }
    private static void near(JsonObject o,String key,double expected) { check(Math.abs(o.get(key).getAsDouble()-expected)<1e-9,key); }
    private static void check(boolean b,String message) { if(!b) throw new AssertionError(message); }
    private static void rejects(Runnable r) { try {r.run();throw new AssertionError("Accepted invalid evaluation input");}catch(IllegalArgumentException | JsonParseException expected){} }
}
