package com.diyalamp.ai;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Offline scoring of externally supplied labels; does not decide semantic support or call a model. */
public final class SemanticSupportEvaluation {
    public enum Label { SUPPORTS, CONTRADICTS, INSUFFICIENT_CONTEXT, ABSTAIN }
    public record Case(String id, String family, String split, String claim,
                       List<DocumentReviewAgent.Evidence> sources, Label expected, String rationale) {
        public Case {
            identifier(id); identifier(family);
            if (!Set.of("development", "held_out").contains(split) || expected == null || expected == Label.ABSTAIN)
                throw new IllegalArgumentException("Invalid case metadata.");
            DocumentReviewAgent.Snapshot.of(claim); DocumentReviewAgent.Snapshot.of(rationale);
            sources = List.copyOf(sources);
            if (sources.isEmpty() || sources.size() > 10 || sources.stream().map(DocumentReviewAgent.Evidence::id).distinct().count() != sources.size())
                throw new IllegalArgumentException("Invalid case sources.");
        }
    }
    /** Bad offsets or invented quotes are scored as invalid citations, not silently repaired. */
    public record Citation(String sourceId, int start, int end, String quote) {
        public Citation { Objects.requireNonNull(sourceId); Objects.requireNonNull(quote); }
    }
    public record Prediction(String id, Label label, List<Citation> citations) {
        public Prediction {
            identifier(id); Objects.requireNonNull(label); citations = List.copyOf(citations);
            if (citations.size() > 10 || new HashSet<>(citations).size() != citations.size() || label == Label.ABSTAIN && !citations.isEmpty())
                throw new IllegalArgumentException("Invalid prediction citations.");
        }
    }
    private SemanticSupportEvaluation() { }

    public static List<Case> readCases(String json) {
        List<Case> cases = new ArrayList<>();
        for (JsonElement element : ReviewJson.parse(json).getAsJsonArray()) {
            JsonObject c = element.getAsJsonObject(); keys(c, "id", "family", "split", "claim", "sources", "expected", "rationale");
            List<DocumentReviewAgent.Evidence> sources = new ArrayList<>();
            for (JsonElement value : c.getAsJsonArray("sources")) {
                JsonObject s = value.getAsJsonObject(); keys(s, "id", "excerpt");
                sources.add(new DocumentReviewAgent.Evidence(string(s, "id"), string(s, "excerpt")));
            }
            cases.add(new Case(string(c,"id"), string(c,"family"), string(c,"split"), string(c,"claim"), sources,
                    Label.valueOf(string(c,"expected")), string(c,"rationale")));
        }
        validateCases(cases); return List.copyOf(cases);
    }
    public static List<Prediction> readPredictions(String json) {
        List<Prediction> predictions = new ArrayList<>();
        for (JsonElement element : ReviewJson.parse(json).getAsJsonArray()) {
            JsonObject p = element.getAsJsonObject(); keys(p, "id", "label", "citations");
            List<Citation> citations = new ArrayList<>();
            for (JsonElement value : p.getAsJsonArray("citations")) {
                JsonObject c = value.getAsJsonObject(); keys(c, "source_id", "start", "end", "quote");
                citations.add(new Citation(string(c,"source_id"), integer(c,"start"), integer(c,"end"), string(c,"quote")));
            }
            predictions.add(new Prediction(string(p,"id"), Label.valueOf(string(p,"label")), citations));
        }
        return List.copyOf(predictions);
    }
    public static JsonObject score(List<Case> cases, List<Prediction> predictions) {
        cases = List.copyOf(cases); predictions = List.copyOf(predictions); validateCases(cases);
        Set<String> known = new HashSet<>(); cases.forEach(c -> known.add(c.id()));
        Map<String, Prediction> byId = new HashMap<>();
        for (Prediction p : predictions)
            if (!known.contains(p.id()) || byId.put(p.id(), p) != null) throw new IllegalArgumentException("Unknown or repeated case prediction.");
        int answered = 0, correct = 0, abstained = 0, missing = 0, quotations = 0, exact = 0, citedCases = 0;
        int missedProblems = 0, falseAlarms = 0;
        int[][] confusion = new int[3][5]; // three labels, then abstain and missing
        JsonArray perCase = new JsonArray();
        for (Case c : cases) {
            Prediction p = byId.get(c.id()); int column = p == null ? 4 : p.label().ordinal();
            confusion[c.expected().ordinal()][column]++;
            if (p == null) missing++;
            else if (p.label() == Label.ABSTAIN) abstained++;
            else {
                answered++; if (p.label() == c.expected()) correct++;
                if (p.label() == Label.SUPPORTS && c.expected() != Label.SUPPORTS) missedProblems++;
                if (c.expected() == Label.SUPPORTS && p.label() != Label.SUPPORTS) falseAlarms++;
            }
            int caseExact = 0;
            if (p != null) {
                quotations += p.citations().size();
                if (!p.citations().isEmpty()) citedCases++;
                for (Citation citation : p.citations()) if (isExact(c, citation)) caseExact++;
                exact += caseExact;
            }
            JsonObject row = new JsonObject(); row.addProperty("id", c.id()); row.addProperty("expected", c.expected().name());
            row.addProperty("predicted", p == null ? "MISSING" : p.label().name()); row.addProperty("exact_citations", caseExact);
            row.addProperty("citation_count", p == null ? 0 : p.citations().size()); perCase.add(row);
        }
        JsonObject report = new JsonObject(); report.addProperty("case_count", cases.size()); report.addProperty("answered_count", answered);
        report.addProperty("correct_count", correct); report.addProperty("abstained_count", abstained); report.addProperty("missing_count", missing);
        ratio(report,"coverage", answered, cases.size()); ratio(report,"accuracy_all_cases", correct, cases.size());
        ratio(report,"accuracy_answered", correct, answered); report.addProperty("unsupported_claims_marked_supported", missedProblems);
        report.addProperty("supported_claims_flagged", falseAlarms); report.addProperty("citation_count", quotations);
        report.addProperty("exact_citation_count", exact); ratio(report,"exact_citation_fraction", exact, quotations);
        ratio(report,"citation_coverage", citedCases, cases.size());
        JsonObject matrix = new JsonObject(), perLabel = new JsonObject(); double f1Sum = 0; int presentLabels = 0;
        for (int expected = 0; expected < 3; expected++) {
            JsonObject row = new JsonObject(); int gold = 0, predicted = 0;
            for (int column = 0; column < 5; column++) { row.addProperty(column == 4 ? "MISSING" : Label.values()[column].name(), confusion[expected][column]); gold += confusion[expected][column]; }
            for (int other = 0; other < 3; other++) predicted += confusion[other][expected];
            int tp = confusion[expected][expected]; double f1 = gold + predicted == 0 ? 0 : 2.0 * tp / (gold + predicted);
            JsonObject metrics = new JsonObject(); metrics.addProperty("gold_count", gold); ratio(metrics,"precision",tp,predicted); ratio(metrics,"recall",tp,gold); metrics.addProperty("f1", f1);
            if (gold > 0) { f1Sum += f1; presentLabels++; }
            perLabel.add(Label.values()[expected].name(), metrics); matrix.add(Label.values()[expected].name(), row);
        }
        report.addProperty("macro_f1_present_gold_labels", f1Sum / presentLabels);
        report.add("per_label", perLabel); report.add("confusion", matrix); report.add("cases", perCase);
        report.addProperty("notice", "Scores compare supplied labels with provisional synthetic labels. Exact citations do not prove semantic support. No model was run by this scorer.");
        return report;
    }
    private static boolean isExact(Case c, Citation quote) {
        for (var source : c.sources()) if (source.id().equals(quote.sourceId())) {
            String text = source.excerpt();
            return quote.start() >= 0 && quote.end() > quote.start() && quote.end() <= text.length()
                    && boundary(text, quote.start()) && boundary(text, quote.end())
                    && !quote.quote().isBlank() && text.substring(quote.start(), quote.end()).equals(quote.quote());
        }
        return false;
    }
    private static boolean boundary(String text, int i) { return i == 0 || i == text.length() || !(Character.isHighSurrogate(text.charAt(i-1)) && Character.isLowSurrogate(text.charAt(i))); }
    private static void validateCases(List<Case> cases) {
        if (cases.isEmpty() || cases.size() > 1000 || cases.stream().map(Case::id).distinct().count() != cases.size()) throw new IllegalArgumentException("Invalid evaluation cases.");
    }
    private static void ratio(JsonObject o, String name, int a, int b) { if (b == 0) o.add(name,JsonNull.INSTANCE); else o.addProperty(name,(double)a/b); }
    private static void identifier(String value) { if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid evaluation identifier."); }
    private static String string(JsonObject o, String key) {
        JsonElement v = o.get(key); if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string."); return v.getAsString();
    }
    private static int integer(JsonObject o, String key) {
        JsonElement v = o.get(key); if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || !v.toString().matches("-?(0|[1-9][0-9]*)")) throw new IllegalArgumentException("Expected integer."); return Integer.parseInt(v.toString());
    }
    private static void keys(JsonObject o, String... keys) { if (!o.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("Unexpected evaluation fields."); }
    private static String read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(250_001); if (bytes.length > 250_000) throw new IOException("Evaluation file too large.");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Supply case JSON and prediction JSON paths.");
        System.out.println(new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(score(readCases(read(Path.of(args[0]))), readPredictions(read(Path.of(args[1]))))));
    }
}
