package com.diyalamp.notion;

import com.google.gson.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public final class NotionClientTest {
    private static final String PAGE="00000000000000000000000000000001", BLOCK="00000000000000000000000000000002";
    private static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
    private static JsonObject list(String type,boolean nested,boolean more,String cursor) {
        return json("{\"results\":[{\"id\":\""+BLOCK+"\",\"type\":\""+type+"\",\"has_children\":"+nested+",\""+type+"\":{\"rich_text\":[{\"plain_text\":\"Synthetic evidence\"}]}}],\"has_more\":"+more+",\"next_cursor\":\""+cursor+"\"}");
    }
    public static void main(String[] args) throws Exception {
        AtomicInteger pageReads=new AtomicInteger(), blockReads=new AtomicInteger();
        NotionClient client=new NotionClient(path->{
            if(path.startsWith("pages/")) { pageReads.incrementAndGet(); return json("{\"last_edited_time\":\"revision-a\"}"); }
            blockReads.incrementAndGet();
            if(path.startsWith("blocks/"+BLOCK)) return json("{\"results\":[],\"has_more\":false}");
            if(path.contains("start_cursor=")) return json("{\"results\":[],\"has_more\":false}");
            return list("paragraph",true,true,"opaque cursor");
        });
        NotionClient.Draft draft=client.read(PAGE);
        check(draft.text().equals("Synthetic evidence")); check(draft.blocks().get(0).id().equals(BLOCK));
        check(pageReads.get()==2 && blockReads.get()==3);
        AtomicInteger revision=new AtomicInteger();
        NotionClient changing=new NotionClient(path->path.startsWith("pages/") ? json("{\"last_edited_time\":\""+revision.incrementAndGet()+"\"}") : list("paragraph",false,false,""));
        reject(()->changing.read(PAGE));
        NotionClient unsupported=new NotionClient(path->path.startsWith("pages/") ? json("{\"last_edited_time\":\"a\"}") : list("image",false,false,""));
        reject(()->unsupported.read(PAGE));
        NotionClient denied=new NotionClient(path->{throw new IOException("Access denied");}); reject(()->denied.read(PAGE));
        NotionClient malformed=new NotionClient(path->json("{}")); reject(()->malformed.read(PAGE));
        try { NotionClient.normalizeId("../other-host"); throw new AssertionError(); } catch(IllegalArgumentException expected) { }
        System.out.println("PASS: Notion pagination, nesting, attribution, changed source, unsupported content, denied access, and malformed response.");
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void reject(Operation action) throws Exception { try { action.run(); throw new AssertionError("Expected failure"); } catch(IOException expected) { } }
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
}
