package com.diyalamp.workflow;

import com.diyalamp.Main;
import java.nio.file.*;
import java.util.*;

public final class SessionStoreTest {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("diyalamp-test-");
        try {
            Path source=dir.resolve("draft.txt"), file=dir.resolve("session.review.properties");
            Files.writeString(source,"A synthetic proposal.");
            ReviewSession s=new ReviewSession(Files.readString(source));
            s.submit(new PhaseResult(Phase.ARGUMENT_MAP,"Missing evidence",List.of("What supports the claim?")));
            SessionStore.save(file,new SessionStore.Saved(source,s));
            s=SessionStore.load(file).review(); check(s.state()==ReviewSession.State.NEEDS_INPUT);
            s.respond("Synthetic evidence"); SessionStore.save(file,new SessionStore.Saved(source,s));
            s=SessionStore.load(file).review(); check(s.answered()); check(s.responses().get(Phase.ARGUMENT_MAP).equals("Synthetic evidence"));
            for(Phase p:Phase.values()) s.submit(new PhaseResult(p,"Checked "+p.label(),List.of()));
            SessionStore.save(file,new SessionStore.Saved(source,s));
            check(SessionStore.load(file).review().state()==ReviewSession.State.AWAITING_APPROVAL);
            Files.writeString(source,"Changed draft");
            try { Main.run(new String[]{"approve",file.toString()}); throw new AssertionError("Stale approval accepted"); }
            catch(IllegalStateException expected) { }
            check(SessionStore.load(file).review().state()==ReviewSession.State.AWAITING_APPROVAL);
            Files.writeString(source,"A synthetic proposal."); Main.run(new String[]{"approve",file.toString()});
            check(SessionStore.load(file).review().state()==ReviewSession.State.READY);
            check(SessionStore.load(file).review().responses().get(Phase.ARGUMENT_MAP).equals("Synthetic evidence"));
            Files.writeString(file,"version=99\n");
            try { SessionStore.load(file); throw new AssertionError("Invalid version accepted"); }
            catch(java.io.IOException expected) { }
            System.out.println("PASS: persisted evidence gates, exact context, approval, stale source, and invalid snapshot.");
        } finally {
            try(var paths=Files.walk(dir)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
    private static void check(boolean value) { if(!value) throw new AssertionError(); }
}
