package com.diyalamp.notion;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

/** Fixed-origin transport. Errors never expose credential headers or response bodies. */
final class NotionHttp {
    private final String token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    NotionHttp(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{1,512}")) throw new IllegalArgumentException("Notion token is required.");
        this.token=token;
    }
    JsonObject get(String path) throws IOException,InterruptedException { return send("GET",path,null); }
    JsonObject post(String path,JsonObject data) throws IOException,InterruptedException { return send("POST",path,data); }
    private JsonObject send(String method,String path,JsonObject data) throws IOException,InterruptedException {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create("https://api.notion.com/v1/"+path)).timeout(Duration.ofSeconds(30))
            .header("Authorization","Bearer "+token).header("Notion-Version","2026-03-11");
        if(data==null) builder.GET(); else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(data.toString()));
        HttpResponse<InputStream> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream body=response.body()) {
            if(response.statusCode()<200 || response.statusCode()>=300) {
                String retry=response.headers().firstValue("Retry-After").filter(v->v.matches("[0-9]{1,8}")).map(v->" Wait at least "+v+" seconds before retrying a read.").orElse("");
                throw new IOException("Notion "+method+" failed (HTTP "+response.statusCode()+")."+retry+(method.equals("POST")?" Publication may have succeeded; do not repeat until reconciled.":" Check access or retry later."));
            }
            byte[] bytes=body.readNBytes(2_000_001);
            if(bytes.length>2_000_000) throw new IOException("Notion response exceeds the size limit.");
            try { return JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch(RuntimeException e) { throw new IOException("Notion returned an invalid response."); }
        }
    }
}
