package com.diyalamp.notion;

import com.diyalamp.workflow.*;
import com.diyalamp.report.ReviewReport;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Publishes a separate child page; never mutates the reviewed source blocks. */
public final class NotionPublisher {
    @FunctionalInterface public interface Writer { JsonObject post(String path,JsonObject body) throws IOException,InterruptedException; }
    public record Source(String pageId,String revision,String fingerprint) {
        public Source { pageId=NotionClient.normalizeId(pageId); Objects.requireNonNull(revision); Objects.requireNonNull(fingerprint); }
        public void verify(NotionClient.Draft current,ReviewSession session) {
            if(!pageId.equals(current.pageId()) || !revision.equals(current.revision()) || !fingerprint.equals(current.fingerprint()) || !fingerprint.equals(session.fingerprint()))
                throw new IllegalStateException("The Notion source changed. Import it into a new session before approval or publication.");
        }
    }
    private final NotionClient reader;
    private final Writer writer;
    public NotionPublisher(NotionClient reader,Writer writer) { this.reader=reader; this.writer=writer; }
    public static NotionPublisher connect(String token) {
        NotionHttp http=new NotionHttp(token); return new NotionPublisher(new NotionClient(http::get),http::post);
    }
    public String publish(Source source,ReviewSession session,String parentId,String reference) throws IOException,InterruptedException {
        JsonObject request=prepare(session,parentId,reference);
        source.verify(reader.read(source.pageId()),session);
        JsonObject response=writer.post("pages",request);
        try { return NotionClient.normalizeId(response.get("id").getAsString()); }
        catch(RuntimeException e) { throw new IOException("Publication response was incomplete. Reconcile before repeating the write."); }
    }
    public static JsonObject prepare(ReviewSession session,String parentId,String reference) {
        if(session.state()!=ReviewSession.State.READY) throw new IllegalStateException("Author approval is required before publishing.");
        if(reference==null || !reference.matches("[a-f0-9]{16}")) throw new IllegalArgumentException("Invalid publication reference.");
        JsonObject parent=new JsonObject(); parent.addProperty("page_id",NotionClient.normalizeId(parentId));
        JsonObject title=new JsonObject(); title.add("title",rich("diyalamp review "+reference));
        JsonObject properties=new JsonObject(); properties.add("title",title);
        JsonArray children=new JsonArray();
        String report=ReviewReport.render(session);
        for(int start=0;start<report.length();) {
            int end=Math.min(start+1900,report.length());
            if(end<report.length() && Character.isHighSurrogate(report.charAt(end-1))) end--;
            JsonObject paragraph=new JsonObject(); paragraph.add("rich_text",rich(report.substring(start,end)));
            JsonObject block=new JsonObject(); block.addProperty("object","block"); block.addProperty("type","paragraph"); block.add("paragraph",paragraph); children.add(block); start=end;
        }
        if(children.size()>100) throw new IllegalArgumentException("Review is too large for one safe publication. Export it locally instead.");
        JsonObject request=new JsonObject(); request.add("parent",parent); request.add("properties",properties); request.add("children",children);
        if(request.toString().getBytes(StandardCharsets.UTF_8).length>450_000) throw new IllegalArgumentException("Review exceeds the publication payload limit. Export locally instead.");
        return request;
    }
    private static JsonArray rich(String text) {
        JsonObject value=new JsonObject(); value.addProperty("content",text);
        JsonObject item=new JsonObject(); item.addProperty("type","text"); item.add("text",value);
        JsonArray array=new JsonArray(); array.add(item); return array;
    }
}
