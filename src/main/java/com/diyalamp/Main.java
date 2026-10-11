package com.diyalamp;

import com.diyalamp.workflow.*;
import com.diyalamp.notion.*;
import com.diyalamp.report.ReviewReport;
import com.diyalamp.local.*;
import com.diyalamp.ai.HostedReviewer;
import java.nio.file.*;
import java.util.*;

/** Explicit commands for draft import, hosted reviews, approval, and publication. */
public final class Main {
    private static final ThreadLocal<Map<String,char[]>> credentials=new ThreadLocal<>();
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) { System.err.println("Unable to complete command: " + e.getMessage()); System.exit(2); }
    }
    public static void run(String[] args) throws Exception {
        if(args.length==3 && args[0].equals("start")) {
            Path target=Path.of(args[2]).toAbsolutePath(); Files.createDirectories(target.getParent());
            try(SessionLock ignored=new SessionLock(target)) { execute(args); }
            return;
        }
        Set<String> locked=Set.of("status","review","record","respond","approve","export","notion-publish","publication-status","publication-resolve");
        if(args.length>=2 && locked.contains(args[0])) {
            if(!Files.isRegularFile(Path.of(args[1]))) throw new IllegalArgumentException("Session file not found. Use start or notion-read first.");
            try(SessionLock ignored=new SessionLock(Path.of(args[1]))) { execute(args); }
        } else execute(args);
    }
    private static void execute(String[] args) throws Exception {
        if(args.length==0 || args[0].equals("--help")) {
            System.out.println("🪔 diyalamp | Defensible writing with AI in Notion.\n"
                + "start <draft.txt> <session.review.properties>\nstatus <session>\n"
                + "record <session> <result.txt> [questions.txt]\nrespond <session> <context.txt>\napprove <session>\n"
                + "notion-read <page-id> <new-output-directory>\nreview <session> <claude-model-id>\nguide <session> <claude-model-id>\nexport <session> <new-report.md>\nnotion-publish <session> <parent-page-id>\npublication-status <session>\npublication-resolve <session> <created-review-page-id>\nAI review requires a separately billed Claude API key and explicit consent."); return;
        }
        if(args[0].equals("notion-read") && args.length==3) {
            String id=NotionClient.normalizeId(args[1]);
            Path directory=Path.of(args[2]).toAbsolutePath().normalize();
            if(Files.exists(directory)) throw new IllegalArgumentException("Choose a new output directory.");
            java.io.Console console=System.console();
            if(console==null) throw new IllegalStateException("Run in a terminal for the hidden Notion token prompt.");
            char[] secret=secret("Notion integration token (hidden): ");
            if(secret==null) throw new IllegalStateException("Token entry cancelled.");
            NotionClient.Draft draft;
            try { draft=NotionClient.connect(new String(secret)).read(id); }
            finally { Arrays.fill(secret,'\0'); }
            Files.createDirectories(directory.getParent());
            Files.createDirectory(directory);
            Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Path source=directory.resolve("draft.txt"); PrivateFiles.create(source,draft.text());
            Properties references=new Properties(); references.setProperty("page",draft.pageId());
            references.setProperty("revision",draft.revision()); references.setProperty("fingerprint",draft.fingerprint());
            for(int i=0;i<draft.blocks().size();i++) {
                references.setProperty("block."+i+".id",draft.blocks().get(i).id());
                references.setProperty("block."+i+".type",draft.blocks().get(i).type());
            }
            try(var writer=Files.newBufferedWriter(directory.resolve("source.properties"))) { references.store(writer,"Notion source attribution"); }
            PrivateFiles.restrict(directory.resolve("source.properties"),false);
            SessionStore.Saved saved=new SessionStore.Saved(source,new ReviewSession(draft.text()));
            SessionStore.save(directory.resolve("session.review.properties"),saved);
            show(saved.review()); System.out.println("Notion draft imported locally. No Notion changes were made."); return;
        }
        if(args[0].equals("guide") && args.length==3) {
            guide(Path.of(args[1]),args[2]); return;
        }
        if(args[0].equals("start") && args.length==3) {
            Path target=Path.of(args[2]);
            if(Files.exists(target)) throw new IllegalArgumentException("Session already exists; choose a new file.");
            Path source=Path.of(args[1]).toAbsolutePath().normalize();
            SessionStore.Saved saved=new SessionStore.Saved(source,new ReviewSession(SessionStore.readText(source)));
            SessionStore.save(target,saved); show(saved.review()); return;
        }
        if(args.length<2) throw new IllegalArgumentException("Use --help for command syntax.");
        Path path=Path.of(args[1]); SessionStore.Saved saved=SessionStore.load(path); ReviewSession s=saved.review();
        switch(args[0]) {
            case "status" -> { require(args,2); show(s); return; }
            case "export" -> { require(args,3); PrivateFiles.create(Path.of(args[2]),ReviewReport.render(s)); System.out.println("Review exported. The source was not changed."); return; }
            case "publication-status" -> {
                require(args,2); Path receipt=receipt(path);
                if(!Files.exists(receipt)) System.out.println("This session has not been published.");
                else { Properties p=PublicationReceipt.read(receipt); System.out.println("Publication: "+p.getProperty("state")+"; reference: "+p.getProperty("reference")); if(p.containsKey("page")) System.out.println("Review page ID: "+p.getProperty("page")); }
                return;
            }
            case "publication-resolve" -> {
                require(args,3); Path receipt=receipt(path); Properties p=PublicationReceipt.read(receipt);
                if(!"PENDING".equals(p.getProperty("state"))) throw new IllegalStateException("No uncertain publication needs reconciliation.");
                NotionClient client=notion(); client.verifyPublication(args[2],p.getProperty("parent"),p.getProperty("reference"));
                PublicationReceipt.complete(receipt,args[2]); System.out.println("Existing review page verified. Publication receipt recovered."); return;
            }
            case "notion-publish" -> {
                require(args,3); String parent=NotionClient.normalizeId(args[2]);
                Path receipt=receipt(path);
                if(Files.exists(receipt)) throw new IllegalStateException("Publication was already attempted. Use publication-status; reconcile a pending receipt before any further action.");
                NotionPublisher.Source source=notionSource(saved);
                if(source==null) throw new IllegalStateException("Publish requires a session imported with notion-read.");
                String reference=UUID.randomUUID().toString().replace("-","").substring(0,16);
                NotionPublisher.prepare(s,parent,reference);
                if(!s.fingerprint().equals(ReviewSession.fingerprint(SessionStore.readText(saved.source())))) throw new IllegalStateException("Local source changed. Start a new review.");
                java.io.Console console=console();
                console.printf("Create a separate review page under Notion parent %s? The source draft will not be overwritten.\n",parent);
                if(!"PUBLISH".equals(console.readLine("Type PUBLISH to continue: "))) { System.out.println("Cancelled. Nothing published."); return; }
                char[] secret=secret("Notion integration token (hidden): ");
                try {
                    NotionClient client=NotionClient.connect(new String(secret)); source.verify(client.read(source.pageId()),s);
                    PublicationReceipt.begin(receipt,reference,parent,s.fingerprint()); PrivateFiles.restrict(receipt,false);
                    String page=NotionPublisher.connect(new String(secret)).publish(source,s,parent,reference);
                    PublicationReceipt.complete(receipt,page); System.out.println("Review published to separate Notion page: "+page);
                } finally { Arrays.fill(secret,'\0'); }
                return;
            }
            case "review" -> {
                require(args,3);
                if(s.nextPhase().isEmpty() || s.state()==ReviewSession.State.NEEDS_INPUT)
                    throw new IllegalStateException("No review can run now; complete the required author input or approval step.");
                if(!s.fingerprint().equals(ReviewSession.fingerprint(SessionStore.readText(saved.source()))))
                    throw new IllegalStateException("Source draft changed. Start a new session.");
                java.io.Console console=System.console();
                if(console==null) throw new IllegalStateException("Run in a terminal for consent and hidden API key entry.");
                console.printf("Send this draft, completed reviews, and author context to Anthropic for %s using %s? API charges apply. One request, at most 4096 output tokens.\n",s.nextPhase().orElseThrow().label(),args[2]);
                if(!"SEND".equals(console.readLine("Type SEND to continue: "))) {
                    System.out.println("Cancelled. No AI request or session change."); return;
                }
                char[] secret=secret("Claude API key (hidden): ");
                if(secret==null) throw new IllegalStateException("Key entry cancelled.");
                PhaseResult result;
                try { result=HostedReviewer.claude(args[2],new String(secret)).review(s); }
                finally { Arrays.fill(secret,'\0'); }
                if(!s.fingerprint().equals(ReviewSession.fingerprint(SessionStore.readText(saved.source()))))
                    throw new IllegalStateException("Source draft changed during review. Result was not applied; API charges may still apply.");
                s.submit(result);
            }
            case "record" -> {
                if(args.length!=3 && args.length!=4) throw new IllegalArgumentException("record requires a result file and optional questions file.");
                List<String> questions=args.length==4 ? SessionStore.readText(Path.of(args[3])).lines().filter(line->!line.isBlank()).toList() : List.of();
                s.submit(new PhaseResult(s.nextPhase().orElseThrow(()->new IllegalStateException("All phases complete.")),SessionStore.readText(Path.of(args[2])),questions));
            }
            case "respond" -> { require(args,3); s.respond(SessionStore.readText(Path.of(args[2]))); }
            case "approve" -> {
                require(args,2);
                if(s.state()!=ReviewSession.State.AWAITING_APPROVAL) throw new IllegalStateException("Complete all five phases before approval.");
                NotionPublisher.Source source=notionSource(saved);
                if(source!=null) source.verify(notion().read(source.pageId()),s);
                s.approve(SessionStore.readText(saved.source()));
            }
            default -> throw new IllegalArgumentException("Unknown command; use --help.");
        }
        SessionStore.save(path,saved); show(s);
    }
    private static Path receipt(Path session) { return session.toAbsolutePath().resolveSibling(session.getFileName()+".publication.properties"); }
    private static NotionPublisher.Source notionSource(SessionStore.Saved saved) throws Exception {
        Path file=saved.source().resolveSibling("source.properties");
        if(!Files.exists(file)) return null;
        if(Files.size(file)>200_000) throw new IllegalStateException("Invalid source metadata.");
        Properties p=new Properties(); try(var in=Files.newBufferedReader(file)) { p.load(in); }
        if(!saved.review().fingerprint().equals(p.getProperty("fingerprint"))) throw new IllegalStateException("Notion source metadata does not match this session.");
        return new NotionPublisher.Source(p.getProperty("page"),p.getProperty("revision"),p.getProperty("fingerprint"));
    }
    private static java.io.Console console() {
        java.io.Console value=System.console();
        if(value==null) throw new IllegalStateException("Run in a terminal for private credential entry and confirmation.");
        return value;
    }
    private static char[] secret(String label) {
        Map<String,char[]> cache=credentials.get();
        if(cache!=null && cache.containsKey(label)) return cache.get(label).clone();
        char[] value=console().readPassword(label);
        if(value==null) throw new IllegalStateException("Credential entry cancelled.");
        if(cache!=null) cache.put(label,value.clone());
        return value;
    }
    private static NotionClient notion() {
        char[] value=secret("Notion integration token (hidden): ");
        try { return NotionClient.connect(new String(value)); } finally { Arrays.fill(value,'\0'); }
    }
    private static void guide(Path path,String model) throws Exception {
        java.io.Console console=console();
        Map<String,char[]> cache=new HashMap<>(); credentials.set(cache);
        try { while(true) {
            ReviewSession s=SessionStore.load(path).review();
            console.printf("\n🪔 diyalamp: %s\n",s.state());
            s.nextPhase().ifPresent(p->console.printf("Next: %s\n",p.label()));
            console.printf("Actions: review, respond, status, approve, export, publish, keys, quit\n");
            String action=console.readLine("Choose an action: ");
            if(action==null || action.trim().equals("quit")) return;
            try {
                switch(action.trim()) {
                    case "review" -> run(new String[]{"review",path.toString(),model});
                    case "respond" -> {
                        console.printf("Enter author context. Type .end alone to finish, or .cancel to discard.\n");
                        StringBuilder context=new StringBuilder(); String line; boolean cancelled=false;
                        while((line=console.readLine())!=null && !line.equals(".end")) {
                            if(line.equals(".cancel")) { cancelled=true; break; }
                            context.append(line).append("\n"); if(context.length()>100_000) throw new IllegalArgumentException("Context exceeds the size limit.");
                        }
                        if(cancelled || line==null) break;
                        try(SessionLock ignored=new SessionLock(path)) {
                            SessionStore.Saved saved=SessionStore.load(path); saved.review().respond(context.toString()); SessionStore.save(path,saved);
                        }
                    }
                    case "keys" -> { cache.values().forEach(value->Arrays.fill(value,'\0')); cache.clear(); console.printf("Cached credentials cleared. The next network action will ask again.\n"); }
                    case "status","approve" -> run(new String[]{action.trim(),path.toString()});
                    case "export" -> { String output=console.readLine("New report path: "); if(output!=null) run(new String[]{"export",path.toString(),output}); }
                    case "publish" -> { String parent=console.readLine("Destination parent page ID: "); if(parent!=null) run(new String[]{"notion-publish",path.toString(),parent}); }
                    default -> console.printf("Choose one of the listed actions.\n");
                }
            } catch(Exception e) { console.printf("Action could not complete: %s\n",e.getMessage()); }
        } } finally { cache.values().forEach(value->Arrays.fill(value,'\0')); credentials.remove(); }
    }
    private static void require(String[] args,int n) { if(args.length!=n) throw new IllegalArgumentException("Unexpected arguments; use --help."); }
    private static void show(ReviewSession s) {
        System.out.println("State: " + s.state());
        for(PhaseResult r:s.results()) System.out.println("\n"+r.phase().label()+"\n"+r.output());
        s.nextPhase().ifPresent(p->System.out.println("\nNext: "+p.label()+"\n"+p.brief()));
        s.pending().ifPresent(r->{ System.out.println("\nPending review: "+r.output()); for(String q:r.questions()) System.out.println("- "+q); });
        if(s.answered()) System.out.println("Author context received. Re-review this phase before advancing.");
        if(s.state()==ReviewSession.State.AWAITING_APPROVAL) System.out.println("Use approve to explicitly mark this unchanged source draft Ready.");
    }
}
