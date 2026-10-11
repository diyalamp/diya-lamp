package com.diyalamp.ai;

import com.diyalamp.workflow.PhaseResult;
import com.diyalamp.workflow.ReviewSession;
import java.io.IOException;

/** Provider-independent contract; workflow state and author approval remain in Java. */
@FunctionalInterface
public interface PhaseReviewer {
    PhaseResult review(ReviewSession session) throws IOException, InterruptedException;
}
