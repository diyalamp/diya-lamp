package com.diyalamp.notion;

import com.diyalamp.workflow.*;
import com.google.gson.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class NotionPublisherTest {
    public static void main(String[] args) throws Exception {
        String page="00000000000000000000000000000001", child="00000000000000000000000000000002", ref="0123456789abcdef";
        ReviewSession s=new ReviewSession("Synthetic draft");
        try { NotionPublisher.prepare(s,page,ref); throw new AssertionError(); } catch(IllegalStateException expected) { }
        for(Phase p:Phase.values()) s.submit(new PhaseResult(p,"Synthetic review "+p.label(),List.of())); s.approve(s.draft());
        NotionClient reader=new NotionClient(path-> {
            if(path.startsWith("pages/")) return JsonParser.parseString("{\"last_edited_time\":\"a\"}").getAsJsonObject();
            return JsonParser.parseString("{\"results\":[{\"id\":\""+child+"\",\"type\":\"paragraph\",\"has_children\":false,\"paragraph\":{\"rich_text\":[{\"plain_text\":\"Synthetic draft\"}]}}],\"has_more\":false}").getAsJsonObject();
        });
        AtomicInteger writes=new AtomicInteger();
        NotionPublisher publisher=new NotionPublisher(reader,(path,body)->{
            writes.incrementAndGet(); check(path.equals("pages")); check(body.getAsJsonObject("parent").get("page_id").getAsString().equals(page));
            check(body.getAsJsonArray("children").size()>0); check(body.getAsJsonArray("children").get(0).getAsJsonObject().get("type").getAsString().equals("heading_1")); JsonObject response=new JsonObject(); response.addProperty("id",child); return response;
        });
        try { publisher.publish(new NotionPublisher.Source(page,"changed",s.fingerprint()),s,page,ref); throw new AssertionError(); } catch(IllegalStateException expected) { }
        check(writes.get()==0);
        check(publisher.publish(new NotionPublisher.Source(page,"a",s.fingerprint()),s,page,ref).equals(child)); check(writes.get()==1);
        Path dir=Files.createTempDirectory("diyalamp-receipt-"); Path receipt=dir.resolve("receipt.properties");
        try {
            PublicationReceipt.begin(receipt,ref,page,s.fingerprint());
            try { PublicationReceipt.begin(receipt,ref,page,s.fingerprint()); throw new AssertionError(); } catch(FileAlreadyExistsException expected) { }
            check(PublicationReceipt.read(receipt).getProperty("state").equals("PENDING"));
            PublicationReceipt.complete(receipt,child); check(PublicationReceipt.read(receipt).getProperty("page").equals(child));
        } finally { Files.deleteIfExists(receipt); Files.delete(dir); }
        System.out.println("PASS: author approval before publication, stale source prevents writes, separate page payload, and durable duplicate guard.");
    }
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
}
