package com.diyalamp.ai;

import com.google.gson.*;
import java.util.*;

public final class ReviewJsonTest {
    public static void main(String[] args) {
        JsonElement valid = ReviewJson.parse(" {\"n\":3.0,\"s\":\"🪔\\n\",\"array\":[true,false,null,{\"n\":1}]} \n");
        check(valid.getAsJsonObject().get("n").toString().equals("3.0"), "do not normalize fractional offset lexemes");
        check(ReviewJson.parse("{\"n\":3e0}").getAsJsonObject().get("n").toString().equals("3e0"), "preserve exponent lexemes");
        for (String invalid : List.of(
                "", " ", "{\"a\":1,\"a\":2}", "{\"a\":null,\"a\":null}",
                "{\"a\":1,\"\\u0061\":2}", "{\"outer\":[{\"a\":1,\"a\":2}]}",
                "/* comment */{}", "{} // comment", "{'a':1}", "{a:1}",
                "{\"a\":1,}", "[1,]", "{\"a\":NaN}", "{\"a\":Infinity}",
                "{}{}", "null false", "{\"a\":\"raw\nnewline\"}",
                "{\"a\":01}", "{\"a\":+1}", "{\"a\":.1}",
                "{\"a\":\"\\uD800\"}", "{\"\\uDC00\":1}",
                "[".repeat(1000) + "0" + "]".repeat(1000))) {
            reject(invalid);
        }
        reject(" ".repeat(250_001));
        reject(null);
        check(ReviewJson.parse("{\"a\":1,\"b\":{\"a\":2}}").isJsonObject(), "names may repeat in distinct objects");
        check(ReviewJson.parse("[".repeat(16) + "0" + "]".repeat(16)).isJsonArray(), "bounded valid nesting");
        reject("[".repeat(17) + "0" + "]".repeat(17));
        System.out.println("PASS: strict review JSON, duplicate and escaped-key rejection, bounded nesting, Unicode and lexeme preservation.");
    }
    private static void reject(String raw) {
        try { ReviewJson.parse(raw); throw new AssertionError("Accepted malformed review JSON"); }
        catch (JsonParseException expected) {
            check(expected.getMessage().equals("Invalid review JSON."), "sanitized failure");
            check(expected.getCause() == null, "no private parser cause retained");
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
