package com.diyalamp.workflow;

public enum Phase {
    ARGUMENT_MAP("Map the argument", "Separate claims, supplied evidence, connecting assumptions, and limits."),
    STRESS_TEST("Stress-test the proposal", "Identify the strongest objections, alternatives, and plausible failure causes."),
    ASSUMPTION_CHECK("Question the assumptions", "Check certainty and framing; ask for context rather than diagnosing bias."),
    DECISION_FRAME("Define the decision", "Specify alternatives, reversibility, measures, timing, and change-of-mind criteria."),
    FINAL_STRUCTURE("Shape the final draft", "Lead with the recommendation and group support; preserve uncertainty.");

    private final String label;
    private final String brief;
    Phase(String label, String brief) { this.label = label; this.brief = brief; }
    public String label() { return label; }
    public String brief() { return brief; }
}
