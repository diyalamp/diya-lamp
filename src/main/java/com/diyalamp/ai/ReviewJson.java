package com.diyalamp.ai;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;

/** Fail-closed syntax boundary for untrusted review output before schema validation.
 * Two bounded passes preserve number lexemes used by the strict offset validator.
 */
final class ReviewJson {
    private static final int MAX_DEPTH = 16;
    private ReviewJson() { }

    static JsonElement parse(String raw) {
        if (raw == null || raw.length() > 250_000) throw invalid();
        try (JsonReader reader = new JsonReader(new StringReader(raw))) {
            reader.setStrictness(Strictness.STRICT);
            validate(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
        } catch (IOException | IllegalStateException malformed) {
            // Do not retain parser errors containing user text or JSON paths.
            throw invalid();
        }
        return JsonParser.parseString(raw);
    }

    private static void validate(JsonReader reader, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw invalid();
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName(); unicode(name);
                    if (!names.add(name)) throw invalid();
                    validate(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) validate(reader, depth + 1);
                reader.endArray();
            }
            case STRING -> unicode(reader.nextString());
            case NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw invalid();
        }
    }
    private static void unicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }
    private static JsonSyntaxException invalid() { return new JsonSyntaxException("Invalid review JSON."); }
}
