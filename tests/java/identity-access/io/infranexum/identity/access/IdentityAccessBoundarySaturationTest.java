package io.infranexum.identity.access;

import static org.junit.jupiter.api.Assertions.*;

import io.infranexum.core.contracts.DomainIdentifier;
import io.infranexum.identity.access.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Saturates independent IAM policy/scope/temporal predicates required by fail-closed authorization. */
final class IdentityAccessBoundarySaturationTest {
    private static final DomainIdentifier A=id(1), B=id(2), ORG=id(3), SUB=id(4), OWNER=id(5), APPROVER=id(6);
    private static final Instant T=Instant.parse("2026-08-16T18:00:00Z");

    @Test void authorizationScopeCoversEveryHierarchyOperand() {
        var platform=AuthorizationScope.platform(); var org=AuthorizationScope.organization(ORG); var sub=AuthorizationScope.subdivision(ORG,SUB);
        assertTrue(platform.covers(platform)); assertTrue(platform.covers(org)); assertTrue(platform.covers(sub));
        assertFalse(org.covers(platform)); assertTrue(org.covers(sub)); assertTrue(org.covers(org));
        assertFalse(org.covers(AuthorizationScope.organization(id(99))));
        assertTrue(sub.covers(sub)); assertFalse(sub.covers(org)); assertFalse(sub.covers(AuthorizationScope.subdivision(ORG,id(98))));
        assertThrows(IllegalArgumentException.class,()->new AuthorizationScope(ScopeKind.PLATFORM,ORG,null));
        assertThrows(IllegalArgumentException.class,()->new AuthorizationScope(ScopeKind.PLATFORM,null,SUB));
        assertThrows(NullPointerException.class,()->new AuthorizationScope(ScopeKind.ORGANIZATION,null,null));
        assertThrows(IllegalArgumentException.class,()->new AuthorizationScope(ScopeKind.ORGANIZATION,ORG,SUB));
        assertThrows(NullPointerException.class,()->new AuthorizationScope(ScopeKind.SUBDIVISION,ORG,null));
        assertThrows(NullPointerException.class,()->sub.covers(null));
    }

    @Test void temporalAssignmentsExerciseOpenClosedAndRevokedIntervals() {
        RoleAssignment open=new RoleAssignment(A,B,AssignmentActorType.USER,A,AuthorizationScope.organization(ORG),T,null,null,null);
        assertFalse(open.effectiveAt(T.minusNanos(1))); assertTrue(open.effectiveAt(T)); assertTrue(open.effectiveAt(T.plusSeconds(100)));
        RoleAssignment bounded=new RoleAssignment(A,B,AssignmentActorType.USER,A,AuthorizationScope.organization(ORG),T,T.plusSeconds(10),null,null);
        assertTrue(bounded.effectiveAt(T.plusSeconds(9))); assertFalse(bounded.effectiveAt(T.plusSeconds(10)));
        RoleAssignment revoked=new RoleAssignment(A,B,AssignmentActorType.USER,A,AuthorizationScope.organization(ORG),T,null,T.plusSeconds(1),B);
        assertFalse(revoked.effectiveAt(T.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new RoleAssignment(A,B,AssignmentActorType.USER,A,AuthorizationScope.organization(ORG),T,T.minusSeconds(1),null,null));
        assertThrows(IllegalArgumentException.class,()->new RoleAssignment(A,B,AssignmentActorType.USER,A,AuthorizationScope.organization(ORG),T,null,T,B==null?A:null));
        assertThrows(IllegalArgumentException.class,()->new RoleAssignment(A,B,AssignmentActorType.USER,A,AuthorizationScope.organization(ORG),T,null,null,B));

        UserMembership membership=new UserMembership(A,B,ORG,SUB,T,T.plusSeconds(10),null);
        assertFalse(membership.effectiveAt(T.minusNanos(1))); assertTrue(membership.effectiveAt(T)); assertFalse(membership.effectiveAt(T.plusSeconds(10)));
        assertFalse(new UserMembership(A,B,ORG,SUB,T,null,T.plusSeconds(1)).effectiveAt(T.plusSeconds(2)));
        assertThrows(IllegalArgumentException.class,()->new UserMembership(A,B,ORG,SUB,T,T.minusNanos(1),null));
    }

    @Test void policyConditionsAndTargetSelectorsCoverIndependentOperands() {
        PolicyCondition existsTrue=new PolicyCondition(PolicyAttributeSource.SUBJECT,"subject.enabled",PolicyOperator.EXISTS,"TRUE");
        assertEquals("true",existsTrue.expectedValue());
        assertThrows(IllegalArgumentException.class,()->new PolicyCondition(PolicyAttributeSource.SUBJECT,"subject.enabled",PolicyOperator.EXISTS,"yes"));
        assertThrows(IllegalArgumentException.class,()->new PolicyCondition(PolicyAttributeSource.SUBJECT,"1bad",PolicyOperator.EQUALS,"x"));
        assertThrows(IllegalArgumentException.class,()->new PolicyCondition(PolicyAttributeSource.SUBJECT,"a","x".equals("x")?PolicyOperator.EQUALS:PolicyOperator.EXISTS," x"));
        assertThrows(IllegalArgumentException.class,()->new PolicyCondition(PolicyAttributeSource.SUBJECT,"a",PolicyOperator.EQUALS,""));
        assertThrows(IllegalArgumentException.class,()->new PolicyCondition(PolicyAttributeSource.SUBJECT,"a",PolicyOperator.EQUALS,"x".repeat(257)));
        assertThrows(IllegalArgumentException.class,()->new PolicyCondition(PolicyAttributeSource.SUBJECT,"a",PolicyOperator.EQUALS,"x\0"));

        PolicyRule exact=rule(1,"iam.user.read","user",existsTrue); PolicyRule wildcard=rule(2,"*","*",existsTrue);
        assertTrue(exact.targets("iam.user.read","user")); assertFalse(exact.targets("iam.user.write","user")); assertFalse(exact.targets("iam.user.read","group"));
        assertTrue(wildcard.targets("anything","anything"));
        assertThrows(IllegalArgumentException.class,()->new PolicyRule(A,0,PolicyEffect.PERMIT,"*","*",List.of(existsTrue),Set.of(),null));
        assertThrows(IllegalArgumentException.class,()->new PolicyRule(A,10001,PolicyEffect.PERMIT,"*","*",List.of(existsTrue),Set.of(),null));
        assertThrows(IllegalArgumentException.class,()->new PolicyRule(A,1,PolicyEffect.PERMIT,"bad action","*",List.of(existsTrue),Set.of(),null));
        assertThrows(IllegalArgumentException.class,()->new PolicyRule(A,1,PolicyEffect.PERMIT,"*","x",List.of(existsTrue),Set.of(),null));
        assertThrows(IllegalArgumentException.class,()->new PolicyRule(A,1,PolicyEffect.PERMIT,"*","*",List.of(),Set.of(),null));
        assertThrows(IllegalArgumentException.class,()->new PolicyRule(A,1,PolicyEffect.PERMIT,"*","*",Collections.nCopies(33,existsTrue),Set.of(),null));
        assertThrows(IllegalArgumentException.class,()->new PolicyRule(A,1,PolicyEffect.PERMIT,"*","*",List.of(existsTrue),Set.of(),"x".repeat(501)));
    }

    @Test void policyLifecycleValidatesEveryEvidenceAndTimestampFence() {
        AccessPolicy draft=policy(PolicyState.DRAFT,null,null,null,null,null,T,T,List.of(rule(1,"*","*",condition())));
        assertFalse(draft.systemPolicy()); assertFalse(draft.effectiveAt(T));
        AccessPolicy validated=draft.validatePolicy(T.plusSeconds(1));
        assertThrows(IdentityAccessException.class,()->validated.approve(OWNER,T.plusSeconds(2)));
        AccessPolicy approved=validated.approve(APPROVER,T.plusSeconds(2));
        AccessPolicy active=approved.activate(T.plusSeconds(3));
        assertTrue(active.effectiveAt(T.plusSeconds(3))); assertFalse(active.effectiveAt(T.minusSeconds(1)));
        AccessPolicy deprecated=active.deprecate(T.plusSeconds(4));
        assertEquals(PolicyState.ACTIVE,deprecated.activate(T.plusSeconds(5)).state());
        AccessPolicy retired=deprecated.retire(T.plusSeconds(5)); assertEquals(PolicyState.RETIRED,retired.state());
        assertThrows(IllegalArgumentException.class,()->draft.validatePolicy(T.minusNanos(1)));
        assertThrows(IdentityAccessException.class,()->draft.activate(T.plusSeconds(1)));
        assertThrows(IdentityAccessException.class,()->draft.deprecate(T.plusSeconds(1)));
        assertThrows(IdentityAccessException.class,()->draft.retire(T.plusSeconds(1)));
        AccessPolicy system=new AccessPolicy(A,null,"system.default",1,AccessPolicy.SYSTEM_OWNER_ID,"system",0,AuthorizationScope.platform(),PolicyState.DRAFT,T,null,null,null,null,null,T,T,List.of(rule(1,"*","*",condition())));
        assertTrue(system.systemPolicy()); assertThrows(IdentityAccessException.class,()->system.validatePolicy(T.plusSeconds(1)));

        assertThrows(IllegalArgumentException.class,()->policy(PolicyState.DRAFT,null,null,null,null,null,T.minusSeconds(1),T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->policy(PolicyState.DRAFT,null,null,null,null,null,T,T.minusSeconds(1),List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->policy(PolicyState.APPROVED,null,null,null,null,null,T,T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->policy(PolicyState.DRAFT,APPROVER,null,null,null,null,T,T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->policy(PolicyState.DRAFT,null,T,null,null,null,T,T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->policy(PolicyState.ACTIVE,APPROVER,T,null,null,null,T,T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->policy(PolicyState.DEPRECATED,APPROVER,T,T,null,null,T,T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->policy(PolicyState.RETIRED,APPROVER,T,T,T,null,T,T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->new AccessPolicy(A,ORG,"team.policy",1,OWNER,"purpose",-1,AuthorizationScope.organization(ORG),PolicyState.DRAFT,T,null,null,null,null,null,T,T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->new AccessPolicy(A,ORG,"team.policy",1,OWNER,"purpose",10001,AuthorizationScope.organization(ORG),PolicyState.DRAFT,T,null,null,null,null,null,T,T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->new AccessPolicy(A,ORG,"team.policy",1,OWNER,"purpose",1,AuthorizationScope.organization(id(99)),PolicyState.DRAFT,T,null,null,null,null,null,T,T,List.of(rule(1,"*","*",condition()))));
        assertThrows(IllegalArgumentException.class,()->new AccessPolicy(A,ORG,"team.policy",1,OWNER,"purpose",1,AuthorizationScope.organization(ORG),PolicyState.DRAFT,T,null,null,null,null,null,T,T,List.of()));
        assertThrows(IllegalArgumentException.class,()->new AccessPolicy(A,ORG,"team.policy",1,OWNER,"purpose",1,AuthorizationScope.organization(ORG),PolicyState.DRAFT,T,null,null,null,null,null,T,T,Collections.nCopies(257,rule(1,"a.b.c","aa",condition()))));
        var duplicate=List.of(rule(1,"a.b.c","aa",condition()),rule(1,"a.b.d","bb",condition()));
        assertThrows(IllegalArgumentException.class,()->new AccessPolicy(A,ORG,"team.policy",1,OWNER,"purpose",1,AuthorizationScope.organization(ORG),PolicyState.DRAFT,T,null,null,null,null,null,T,T,duplicate));
    }

    @Test void usersAndSodPairsCoverTerminalAndOrderingBranches() {
        IdentityUser pending=IdentityUser.pending(A,"User.Name","USER@example.com"," User ",T);
        assertEquals("user.name",pending.login()); assertEquals("user@example.com",pending.email());
        IdentityUser active=pending.activate(T.plusSeconds(1)); IdentityUser deleted=active.delete(T.plusSeconds(2));
        assertThrows(IdentityAccessException.class,()->deleted.updateProfile(null,"x",T.plusSeconds(3)));
        assertThrows(IdentityAccessException.class,()->deleted.activate(T.plusSeconds(3)));
        assertNull(IdentityUser.pending(B,"abc",null,"User",T).email()); assertNull(IdentityUser.pending(B,"abc","  ","User",T).email());
        assertThrows(IllegalArgumentException.class,()->IdentityUser.pending(B,"abc","missing-at","User",T));
        assertThrows(IllegalArgumentException.class,()->IdentityUser.pending(B,"abc","a@"+"x".repeat(319),"User",T));
        assertThrows(IllegalArgumentException.class,()->IdentityUser.pending(B,"abc","a@b\n","User",T));
        assertThrows(IllegalArgumentException.class,()->IdentityUser.pending(B,"abc","a@b","x".repeat(201),T));
        assertThrows(IllegalArgumentException.class,()->new Role(A,ORG,"a".repeat(161),"Role",ScopeKind.ORGANIZATION,false,true,T,T,null));
        assertThrows(IllegalArgumentException.class,()->new Permission(A,ORG,"a".repeat(161),"asset","read","normal",ScopeKind.ORGANIZATION,false,true,T,T,null));
        assertThrows(IllegalArgumentException.class,()->new IdentityUser(A,"abc",null,"User",IdentityUserStatus.DELETED,T,T,null));
        assertThrows(IllegalArgumentException.class,()->new IdentityUser(A,"abc",null,"User",IdentityUserStatus.ACTIVE,T,T,T));
        assertThrows(IllegalArgumentException.class,()->IdentityUser.pending(B,"abc",null,"   ",T));

        SeparationOfDutyConstraint ordered=new SeparationOfDutyConstraint(A,B,ORG,B,A,"reason",T,OWNER);
        assertTrue(ordered.firstRoleId().compareTo(ordered.secondRoleId())<0);
        assertEquals(ordered.secondRoleId(),ordered.conflictingRole(ordered.firstRoleId()));
        assertEquals(ordered.firstRoleId(),ordered.conflictingRole(ordered.secondRoleId())); assertNull(ordered.conflictingRole(id(99)));
        assertThrows(IllegalArgumentException.class,()->new SeparationOfDutyConstraint(A,B,ORG,A,A,"reason",T,OWNER));
        SeparationOfDutyDefinition def=new SeparationOfDutyDefinition(B,A,"reason"); assertTrue(def.firstRoleId().compareTo(def.secondRoleId())<0);
        assertThrows(IllegalArgumentException.class,()->new SeparationOfDutyDefinition(A,A,"reason"));
    }

    @Test void commandContextBoundsRejectEmptyAndOverlongFields() {
        assertThrows(IllegalArgumentException.class,()->new io.infranexum.identity.access.application.IdentityAccessCommandContext(A,B,"   ","TEST"));
        assertThrows(IllegalArgumentException.class,()->new io.infranexum.identity.access.application.IdentityAccessCommandContext(A,B,"x".repeat(1025),"TEST"));
    }

    private static PolicyCondition condition(){return new PolicyCondition(PolicyAttributeSource.SUBJECT,"subject.enabled",PolicyOperator.EXISTS,"true");}
    private static PolicyRule rule(int position,String action,String resource,PolicyCondition condition){return new PolicyRule(id(20+position),position,PolicyEffect.PERMIT,action,resource,List.of(condition),Set.of(),null);}
    private static AccessPolicy policy(PolicyState state,DomainIdentifier approvedBy,Instant approvedAt,Instant activatedAt,Instant deprecatedAt,Instant retiredAt,Instant effective,Instant updated,List<PolicyRule> rules){
        return new AccessPolicy(A,ORG,"team.policy",1,OWNER,"purpose",10,AuthorizationScope.organization(ORG),state,effective,approvedBy,approvedAt,activatedAt,deprecatedAt,retiredAt,T,updated,rules);
    }
    private static DomainIdentifier id(long n){return DomainIdentifier.parse("01900000-0000-7000-8000-"+String.format("%012d",n));}
}
