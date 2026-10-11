package com.diyalamp.notion;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import com.diyalamp.workflow.ReviewSession;

/** Draft reader. Page content is data, never executable instructions. */
public final class NotionClient {
    @FunctionalInterface public interface Transport { JsonObject get(String path) throws IOException, InterruptedException; }
    public record BlockText(String id,String type,String text) {}
    public record Draft(String pageId,String revision,List<BlockText> blocks) {
        public Draft { blocks=List.copyOf(blocks); }
        public String text() { return String.join("\n",blocks.stream().map(BlockText::text).toList()); }
        public String fingerprint() { return ReviewSession.fingerprint(text()); }
    }
    private final Transport transport;
    public NotionClient(Transport transport) { this.transport=Objects.requireNonNull(transport); }
    public static NotionClient connect(String token) {
        NotionHttp http=new NotionHttp(token);
        return new NotionClient(http::get);
    }

    public Draft read(String pageId) throws IOException,InterruptedException {
        String id=normalizeId(pageId);
        try {
            JsonObject before=transport.get("pages/"+id);
            if(flag(before,"archived") || flag(before,"in_trash")) throw new IOException("The selected page is archived.");
            String revision=before.get("last_edited_time").getAsString();
            List<BlockText> blocks=new ArrayList<>(); walk(id,blocks,0,new HashSet<>());
            String after=transport.get("pages/"+id).get("last_edited_time").getAsString();
            if(!revision.equals(after)) throw new IOException("The page changed during import. Retry with a stable draft.");
            Draft draft=new Draft(id,revision,blocks);
            if(draft.text().isBlank()) throw new IOException("The page contains no supported draft text.");
            if(draft.text().length()>100_000) throw new IOException("The draft exceeds the size limit.");
            return draft;
        } catch(JsonParseException | IllegalStateException | NullPointerException e) { throw new IOException("Notion returned an incomplete response."); }
    }
    private void walk(String id,List<BlockText> output,int depth,Set<String> visited) throws IOException,InterruptedException {
        if(depth>20 || !visited.add(id)) throw new IOException("Draft nesting exceeds the supported limit.");
        String cursor=null; Set<String> cursors=new HashSet<>(); int pages=0;
        do {
            if(++pages>100) throw new IOException("Draft pagination exceeds the supported limit.");
            JsonObject page=transport.get("blocks/"+id+"/children?page_size=100"+(cursor==null?"":"&start_cursor="+URLEncoder.encode(cursor,java.nio.charset.StandardCharsets.UTF_8)));
            for(JsonElement element:page.getAsJsonArray("results")) {
                JsonObject block=element.getAsJsonObject(); String type=block.get("type").getAsString(); String blockId=normalizeId(block.get("id").getAsString());
                if(flag(block,"archived") || flag(block,"in_trash")) continue;
                if(type.equals("child_page") || type.equals("child_database")) continue;
                JsonObject value=block.getAsJsonObject(type);
                if(Set.of("paragraph","heading_1","heading_2","heading_3","bulleted_list_item","numbered_list_item","to_do","toggle","quote","callout","code").contains(type) && value!=null && value.has("rich_text")) {
                    StringBuilder text=new StringBuilder();
                    for(JsonElement rich:value.getAsJsonArray("rich_text")) text.append(rich.getAsJsonObject().get("plain_text").getAsString());
                    if(!text.isEmpty()) output.add(new BlockText(blockId,type,text.toString()));
                } else if(type.equals("table_row")) {
                    List<String> cells=new ArrayList<>();
                    for(JsonElement cell:value.getAsJsonArray("cells")) {
                        StringBuilder text=new StringBuilder();
                        for(JsonElement rich:cell.getAsJsonArray()) text.append(rich.getAsJsonObject().get("plain_text").getAsString());
                        cells.add(text.toString());
                    }
                    output.add(new BlockText(blockId,type,String.join(" | ",cells)));
                } else if(!Set.of("divider","column","column_list","table").contains(type)) {
                    throw new IOException("Draft contains unsupported block type: "+type+". Use a text-only draft page.");
                }
                if(output.size()>1000 || output.stream().mapToInt(b->b.text().length()).sum()>100_000) throw new IOException("Draft exceeds the size limit.");
                if(flag(block,"has_children")) walk(blockId,output,depth+1,visited);
            }
            if(!flag(page,"has_more")) break;
            cursor=page.get("next_cursor").getAsString();
            if(cursor.isBlank() || !cursors.add(cursor)) throw new IOException("Invalid pagination cursor.");
        } while(true);
    }
    private static boolean flag(JsonObject o,String name) { return o.has(name) && o.get(name).getAsBoolean(); }
    public static String normalizeId(String value) {
        String raw=value.replace("-","");
        if(!raw.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("Supply a Notion page ID, not a URL.");
        return raw.toLowerCase(Locale.ROOT);
    }
}
