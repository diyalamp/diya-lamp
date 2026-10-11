package com.diyalamp.service;

import java.time.Instant;
import java.util.Objects;

/** Server-owned entitlement policy. Never construct its inputs from Notion or browser claims. */
public final class ReviewAccess {
    private ReviewAccess() {}
    public enum Plan { FREE, PERSONAL, ENTERPRISE }
    public enum Billing { CONFIRMED, PAYMENT_FAILED, COLLECTION_PAUSED, UNKNOWN }
    public enum Decision { ALLOW, WRONG_TENANT, NOTION_DISCONNECTED, BILLING_REQUIRED, PERIOD_EXPIRED, ALLOWANCE_EXHAUSTED }

    /** Paid end is exclusive. Cancellation at period end preserves already paid access until this instant. */
    public record Entitlement(String tenant, Plan plan, Billing billing, Instant paidStart,
                              Instant paidEnd, long journeysRemaining) {
        public Entitlement {
            requireTenant(tenant);
            Objects.requireNonNull(plan);
            Objects.requireNonNull(billing);
            if (journeysRemaining < 0) throw new IllegalArgumentException("Negative allowance.");
            if (plan != Plan.FREE && (paidStart == null || paidEnd == null || !paidStart.isBefore(paidEnd)))
                throw new IllegalArgumentException("Paid plans require a valid access period.");
            if (plan == Plan.FREE && (paidStart != null || paidEnd != null))
                throw new IllegalArgumentException("Free access has no paid period.");
        }
    }

    /** fundedJourney is loaded from tenant-owned durable state, not supplied by a client. */
    public static Decision check(String authenticatedTenant, String draftTenant, boolean notionConnected,
                                 boolean fundedJourney, Entitlement entitlement, Instant now) {
        requireTenant(authenticatedTenant);
        requireTenant(draftTenant);
        Objects.requireNonNull(entitlement);
        Objects.requireNonNull(now);
        if (!authenticatedTenant.equals(draftTenant) || !authenticatedTenant.equals(entitlement.tenant()))
            return Decision.WRONG_TENANT;
        if (!notionConnected) return Decision.NOTION_DISCONNECTED;
        if (entitlement.plan() != Plan.FREE) {
            if (entitlement.billing() != Billing.CONFIRMED) return Decision.BILLING_REQUIRED;
            if (now.isBefore(entitlement.paidStart()) || !now.isBefore(entitlement.paidEnd()))
                return Decision.PERIOD_EXPIRED;
        }
        if (!fundedJourney && entitlement.journeysRemaining() == 0) return Decision.ALLOWANCE_EXHAUSTED;
        return Decision.ALLOW;
    }

    private static void requireTenant(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Tenant is required.");
    }
}
