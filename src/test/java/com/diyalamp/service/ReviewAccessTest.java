package com.diyalamp.service;

import java.time.Instant;
import static com.diyalamp.service.ReviewAccess.*;

public final class ReviewAccessTest {
    static final Instant START=Instant.parse("2026-10-01T00:00:00Z"), END=START.plusSeconds(30*86400L);
    public static void main(String[] args) {
        for(Plan plan : new Plan[]{Plan.PERSONAL,Plan.ENTERPRISE}) {
            var paid=new Entitlement("a",plan,Billing.CONFIRMED,START,END,20);
            expect(Decision.ALLOW,check("a","a",true,false,paid,START));
            expect(Decision.PERIOD_EXPIRED,check("a","a",true,true,paid,END));
            expect(Decision.PERIOD_EXPIRED,check("a","a",true,false,paid,START.minusSeconds(1)));
            expect(Decision.WRONG_TENANT,check("a","b",true,false,paid,START));
            expect(Decision.WRONG_TENANT,check("b","b",true,false,paid,START));
            expect(Decision.NOTION_DISCONNECTED,check("a","a",false,true,paid,START));
            for(Billing state : new Billing[]{Billing.PAYMENT_FAILED,Billing.COLLECTION_PAUSED,Billing.UNKNOWN}) {
                var blocked=new Entitlement("a",plan,state,START,END,20);
                expect(Decision.BILLING_REQUIRED,check("a","a",true,true,blocked,START));
            }
            var exhausted=new Entitlement("a",plan,Billing.CONFIRMED,START,END,0);
            expect(Decision.ALLOWANCE_EXHAUSTED,check("a","a",true,false,exhausted,START));
            expect(Decision.ALLOW,check("a","a",true,true,exhausted,START));
        }
        var free=new Entitlement("a",Plan.FREE,Billing.UNKNOWN,null,null,2);
        expect(Decision.ALLOW,check("a","a",true,false,free,START));
        var used=new Entitlement("a",Plan.FREE,Billing.UNKNOWN,null,null,0);
        expect(Decision.ALLOWANCE_EXHAUSTED,check("a","a",true,false,used,START));
        expect(Decision.ALLOW,check("a","a",true,true,used,START));
        try {new Entitlement("a",Plan.PERSONAL,Billing.CONFIRMED,END,START,20);throw new AssertionError("Invalid period accepted");}
        catch(IllegalArgumentException expected) {}
        System.out.println("PASS: tenant ownership, free credits, paid periods, payment failures, paused billing, and funded resumes.");
    }
    static void expect(Decision expected,Decision actual){if(expected!=actual)throw new AssertionError(expected+" != "+actual);}
}
