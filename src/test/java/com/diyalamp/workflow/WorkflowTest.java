package com.diyalamp.workflow;

import java.util.List;

public final class WorkflowTest {
    public static void main(String[] args) {
        ReviewSession s = new ReviewSession("Shorter setup might improve activation.");
        rejects(() -> s.approve(s.draft()), "Premature approval");
        rejects(() -> s.submit(new PhaseResult(Phase.FINAL_STRUCTURE,"Polished conclusion",List.of())), "Phase skipping");
        s.submit(new PhaseResult(Phase.ARGUMENT_MAP,"Cause is unknown",List.of("Why did accounts stop?")));
        check(s.state()==ReviewSession.State.NEEDS_INPUT,"Questions must block progress");
        rejects(() -> s.submit(new PhaseResult(Phase.ARGUMENT_MAP,"Unsupported resolution",List.of())), "Skipping author response");
        rejects(() -> s.respond(" "), "Blank response");
        s.respond("The cause is still unknown. Preserve that limitation.");
        check(s.nextPhase().orElseThrow()==Phase.ARGUMENT_MAP,"Author response must not auto-pass a phase");
        s.submit(new PhaseResult(Phase.ARGUMENT_MAP,"Treat causality as an open hypothesis",List.of()));
        for (Phase p : Phase.values()) if(p!=Phase.ARGUMENT_MAP) s.submit(new PhaseResult(p,"Reviewed with limits visible",List.of()));
        check(s.state()==ReviewSession.State.AWAITING_APPROVAL,"Completion must not mean Ready");
        rejects(() -> s.approve("A modified source"),"Stale source approval");
        s.approve(s.draft());check(s.state()==ReviewSession.State.READY,"Explicit approval must reach Ready");
        rejects(() -> s.results().clear(),"External mutation");
        rejects(() -> new ReviewSession(""),"Empty draft");
        System.out.println("PASS: evidence gate, phase order, author response, stale-source guard, approval, and immutability.");
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void rejects(Runnable action,String name){try{action.run();throw new AssertionError(name+" was accepted");}catch(IllegalArgumentException|IllegalStateException|UnsupportedOperationException expected){}}
}
