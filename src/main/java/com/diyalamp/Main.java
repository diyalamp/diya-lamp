package com.diyalamp;

import com.diyalamp.workflow.*;
import com.diyalamp.notion.NotionClient;
import java.nio.file.*;
import java.util.*;

/** Local workflow commands; Notion and hosted AI adapters follow separately. */
public final class Main {
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) { System.err.println("Unable to complete command: " + e.getMessage()); System.exit(2); }
    }
    public static void run(String[] args) throws Exception {
        if(args.length==0 || args[0].equals("--help")) {
            System.out.println("🪔 diyalamp | Defensible writing with AI in Notion.\n"
                + "start <draft.txt> <session.review.properties>\nstatus <session>\n"
                + "record <session> <result.txt> [questions.txt]\nrespond <session> <context.txt>\napprove <session>\n"
                + "notion-read <page-id> <new-output-directory>\nPhase results are supplied by you; no AI review yet."); return;
        }
        if(args[0].equals("notion-read") && args.length==3) {
            String id=NotionClient.normalizeId(args[1]);
            Path directory=Path.of(args[2]).toAbsolutePath().normalize();
            if(Files.exists(directory)) throw new IllegalArgumentException("Choose a new output directory.");
            java.io.Console console=System.console();
            if(console==null) throw new IllegalStateException("Run in a terminal for the hidden Notion token prompt.");
            char[] secret=console.readPassword("Notion integration token (hidden): ");
            if(secret==null) throw new IllegalStateException("Token entry cancelled.");
            NotionClient.Draft draft;
            try { draft=NotionClient.connect(new String(secret)).read(id); }
            finally { Arrays.fill(secret,'\0'); }
            Files.createDirectory(directory);
            Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Path source=directory.resolve("draft.txt"); Files.writeString(source,draft.text());
            Properties references=new Properties(); references.setProperty("page",draft.pageId());
            references.setProperty("revision",draft.revision()); references.setProperty("fingerprint",draft.fingerprint());
            for(int i=0;i<draft.blocks().size();i++) {
                references.setProperty("block."+i+".id",draft.blocks().get(i).id());
                references.setProperty("block."+i+".type",draft.blocks().get(i).type());
            }
            try(var writer=Files.newBufferedWriter(directory.resolve("source.properties"))) { references.store(writer,"Notion source attribution"); }
            SessionStore.Saved saved=new SessionStore.Saved(source,new ReviewSession(draft.text()));
            SessionStore.save(directory.resolve("session.review.properties"),saved);
            show(saved.review()); System.out.println("Notion draft imported locally. No Notion changes were made."); return;
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
            case "record" -> {
                if(args.length!=3 && args.length!=4) throw new IllegalArgumentException("record requires a result file and optional questions file.");
                List<String> questions=args.length==4 ? SessionStore.readText(Path.of(args[3])).lines().filter(line->!line.isBlank()).toList() : List.of();
                s.submit(new PhaseResult(s.nextPhase().orElseThrow(()->new IllegalStateException("All phases complete.")),SessionStore.readText(Path.of(args[2])),questions));
            }
            case "respond" -> { require(args,3); s.respond(SessionStore.readText(Path.of(args[2]))); }
            case "approve" -> { require(args,2); s.approve(SessionStore.readText(saved.source())); }
            default -> throw new IllegalArgumentException("Unknown command; use --help.");
        }
        SessionStore.save(path,saved); show(s);
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
