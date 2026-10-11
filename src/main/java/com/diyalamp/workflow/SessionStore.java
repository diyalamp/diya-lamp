package com.diyalamp.workflow;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Local snapshots contain private draft text; keep them outside version control. */
public final class SessionStore {
    public record Saved(Path source, ReviewSession review) {}
    public static String readText(Path path) throws IOException {
        if (Files.size(path) > 400_000) throw new IOException("Input exceeds the size limit.");
        return Files.readString(path);
    }
    public static Saved load(Path path) throws IOException {
        if (Files.size(path) > 4_000_000) throw new IOException("Session exceeds the size limit.");
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path)) { p.load(r); }
        try {
            if (!"1".equals(p.getProperty("version"))) throw new IllegalArgumentException("Unsupported session version.");
            ReviewSession s = new ReviewSession(required(p,"draft"));
            int count = Integer.parseInt(required(p,"completed"));
            if (count < 0 || count > Phase.values().length) throw new IllegalArgumentException("Invalid phase count.");
            for (int i = 0; i < count; i++) restore(p,s,i,false);
            if (Boolean.parseBoolean(p.getProperty("pending"))) restore(p,s,count,true);
            if (Boolean.parseBoolean(p.getProperty("approved"))) s.approve(s.draft());
            return new Saved(Path.of(required(p,"source")),s);
        } catch (IllegalArgumentException | IllegalStateException e) { throw new IOException("Invalid session: " + e.getMessage(),e); }
    }
    private static void restore(Properties p, ReviewSession s, int index, boolean pending) {
        Phase phase = s.nextPhase().orElseThrow();
        String key = "phase." + index + ".";
        String context = p.getProperty(key + "context");
        if (context != null && !(pending && Boolean.parseBoolean(p.getProperty("answered")))) {
            s.submit(new PhaseResult(phase,"Context requested",List.of("Supply author context.")));
            s.respond(context);
        }
        int n = Integer.parseInt(p.getProperty(key + "questions","0"));
        if (n < 0 || n > 50 || (!pending && n != 0) || (pending && n == 0)) throw new IllegalArgumentException("Invalid questions.");
        List<String> questions = new ArrayList<>();
        for (int j = 0; j < n; j++) questions.add(required(p,key + "question." + j));
        s.submit(new PhaseResult(phase,required(p,key + "output"),questions));
        if (pending && Boolean.parseBoolean(p.getProperty("answered"))) {
            if (context == null) throw new IllegalArgumentException("Answered phase lacks context.");
            s.respond(context);
        }
    }
    private static String required(Properties p,String key) {
        String value = p.getProperty(key);
        if (value == null) throw new IllegalArgumentException("Missing " + key + ".");
        return value;
    }
    public static void save(Path path,Saved saved) throws IOException {
        Properties p = new Properties();
        ReviewSession s = saved.review();
        p.setProperty("version","1"); p.setProperty("source",saved.source().toAbsolutePath().normalize().toString());
        p.setProperty("draft",s.draft()); p.setProperty("completed",Integer.toString(s.results().size()));
        p.setProperty("pending",Boolean.toString(s.pending().isPresent()));
        p.setProperty("answered",Boolean.toString(s.answered())); p.setProperty("approved",Boolean.toString(s.state()==ReviewSession.State.READY));
        for (int i=0;i<s.results().size();i++) put(p,i,s.results().get(i),s);
        s.pending().ifPresent(r -> put(p,s.results().size(),r,s));
        Path target=path.toAbsolutePath(); Path parent=target.getParent(); Files.createDirectories(parent);
        Path tmp=Files.createTempFile(parent,".review-",".tmp");
        try {
            try { Files.setPosixFilePermissions(tmp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            try (Writer w=Files.newBufferedWriter(tmp)) { p.store(w,"diyalamp private review session"); }
            Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    private static void put(Properties p,int i,PhaseResult r,ReviewSession s) {
        String key="phase."+i+".";
        p.setProperty(key+"output",r.output()); p.setProperty(key+"questions",Integer.toString(r.questions().size()));
        for(int j=0;j<r.questions().size();j++) p.setProperty(key+"question."+j,r.questions().get(j));
        String context=s.responses().get(r.phase()); if(context!=null) p.setProperty(key+"context",context);
    }
}
