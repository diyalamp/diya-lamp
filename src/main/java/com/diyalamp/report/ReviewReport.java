package com.diyalamp.report;

import com.diyalamp.workflow.*;

/** A readable artifact, never an instruction to apply source edits. */
public final class ReviewReport {
    private ReviewReport() {}
    public static String render(ReviewSession session) {
        StringBuilder out=new StringBuilder("# 🪔 diyalamp review\n\nState: "+session.state()+"\nSource fingerprint: "+session.fingerprint()+"\n\n");
        out.append("This review supports judgment; it does not certify truth. Suggested revisions are not applied to the source.\n\n## Reviewed source\n\n").append(session.draft()).append("\n\n");
        for(PhaseResult result:session.results()) {
            out.append("## ").append(result.phase().ordinal()+1).append(". ").append(result.phase().label()).append("\n\n").append(result.output()).append("\n\n");
            String context=session.responses().get(result.phase());
            if(context!=null) out.append("### Author context\n\n").append(context).append("\n\n");
        }
        session.pending().ifPresent(result->{
            out.append("## Pending: ").append(result.phase().label()).append("\n\n").append(result.output()).append("\n\n### Questions\n\n");
            result.questions().forEach(q->out.append("- ").append(q).append("\n"));
            String context=session.responses().get(result.phase());
            if(context!=null) out.append("\n### Author context\n\n").append(context).append("\n");
        });
        return out.toString();
    }
}
