/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the scope value algebra: default equality judgment with a legible refusal,
 * unrecognized types passing untouched, inheritance-based axis refinement judged at
 * the shared level in both directions, composite flattening in both roles, and the
 * guard's merge/dedup/immutability semantics.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public class ScopeValueTest {

    record OtherScope(String value) implements Scope {
    }

    /** A parent axis and its refining child, the inheritance recipe under test. */
    static class RegionScope implements Scope {
        final String region;

        RegionScope(String region) {
            this.region = region;
        }

        @Override
        public boolean matches(Scope candidate) {
            return region.equals(((RegionScope)candidate).region);
        }

        @Override
        public boolean equals(Object o) {
            return o != null && getClass() == o.getClass() && region.equals(((RegionScope)o).region);
        }

        @Override
        public int hashCode() {
            return Objects.hash(getClass(), region);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[" + region + "]";
        }
    }

    static class CityScope extends RegionScope {
        final String city;

        CityScope(String region, String city) {
            super(region);
            this.city = city;
        }

        @Override
        public boolean matches(Scope candidate) {
            if (!super.matches(candidate)) {
                return false;
            }
            return !(candidate instanceof CityScope other) || city.equals(other.city);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && city.equals(((CityScope)o).city);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), city);
        }

        @Override
        public String toString() {
            return "CityScope[" + region + "/" + city + "]";
        }
    }

    /** CityScope's sibling: shares the region axis, adds its own. */
    static class DistrictScope extends RegionScope {
        final String district;

        DistrictScope(String region, String district) {
            super(region);
            this.district = district;
        }

        @Override
        public boolean matches(Scope candidate) {
            if (!super.matches(candidate)) {
                return false;
            }
            return !(candidate instanceof DistrictScope other) || district.equals(other.district);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && district.equals(((DistrictScope)o).district);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), district);
        }

        @Override
        public String toString() {
            return "DistrictScope[" + region + "/" + district + "]";
        }
    }

    /** A third hierarchy level under CityScope. */
    static class BlockScope extends CityScope {
        final String block;

        BlockScope(String region, String city, String block) {
            super(region, city);
            this.block = block;
        }

        @Override
        public boolean matches(Scope candidate) {
            if (!super.matches(candidate)) {
                return false;
            }
            return !(candidate instanceof BlockScope other) || block.equals(other.block);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && block.equals(((BlockScope)o).block);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), block);
        }

        @Override
        public String toString() {
            return "BlockScope[" + region + "/" + city + "/" + block + "]";
        }
    }

    @Test
    void equalValuesPass() throws Exception {
        new TenantScope("t1").pass(new TenantScope("t1"));
    }

    @Test
    void mismatchRefusesLegibly() {
        GuardrailException e = assertThrows(GuardrailException.class,
                () -> new TenantScope("t1").pass(new TenantScope("t2")));
        assertTrue(e.getMessage().contains("t1") && e.getMessage().contains("t2"), e.getMessage());
    }

    @Test
    void unrecognizedTypePassesUntouched() throws Exception {
        new TenantScope("t1").pass(new OtherScope("anything"));
    }

    @Test
    void parentBoundScopeJudgesChildClaimOnTheSharedAxis() throws Exception {
        new RegionScope("emea").pass(new CityScope("emea", "berlin"));
        assertThrows(GuardrailException.class,
                () -> new RegionScope("emea").pass(new CityScope("apac", "tokyo")));
    }

    @Test
    void childBoundScopeJudgesParentClaimOnTheSharedAxis() throws Exception {
        new CityScope("emea", "berlin").pass(new RegionScope("emea"));
        assertThrows(GuardrailException.class,
                () -> new CityScope("emea", "berlin").pass(new RegionScope("apac")));
    }

    @Test
    void childBoundScopeJudgesChildClaimOnBothAxes() throws Exception {
        new CityScope("emea", "berlin").pass(new CityScope("emea", "berlin"));
        assertThrows(GuardrailException.class,
                () -> new CityScope("emea", "berlin").pass(new CityScope("emea", "munich")));
    }

    @Test
    void siblingSubtypesAreJudgedOnTheirSharedAxis() throws Exception {
        new CityScope("emea", "berlin").pass(new DistrictScope("emea", "mitte"));
        new DistrictScope("emea", "mitte").pass(new CityScope("emea", "berlin"));
        assertThrows(GuardrailException.class,
                () -> new CityScope("emea", "berlin").pass(new DistrictScope("apac", "shibuya")));
        assertThrows(GuardrailException.class,
                () -> new DistrictScope("emea", "mitte").pass(new CityScope("apac", "tokyo")));
    }

    @Test
    void threeLevelChainJudgesAtEverySharedDepth() throws Exception {
        BlockScope bound = new BlockScope("emea", "berlin", "b7");
        bound.pass(new RegionScope("emea"));
        bound.pass(new CityScope("emea", "berlin"));
        bound.pass(new BlockScope("emea", "berlin", "b7"));
        assertThrows(GuardrailException.class, () -> bound.pass(new RegionScope("apac")));
        assertThrows(GuardrailException.class, () -> bound.pass(new CityScope("emea", "munich")));
        assertThrows(GuardrailException.class, () -> bound.pass(new BlockScope("emea", "berlin", "b8")));
        new RegionScope("emea").pass(bound);
        assertThrows(GuardrailException.class, () -> new CityScope("emea", "munich").pass(bound));
        assertThrows(GuardrailException.class,
                () -> bound.pass(new DistrictScope("apac", "shibuya")), "grand-sibling still shares the root axis");
    }

    @Test
    void nestedAuthoritiesOnOneAxisChainBothPin() throws Exception {
        // outer authority bound to the region, inner to a city within it: the merged
        // guard holds both, and each judges every claim at its own level
        ScopeGuard guard = new ScopeGuard(new RegionScope("emea")).merge(new ScopeGuard(new CityScope("emea", "berlin")));
        assertEquals(2, guard.getScopes().size(), "parent and child scopes are distinct members");
        guard.passAll(new CityScope("emea", "berlin"));
        guard.passAll(new RegionScope("emea"));
        assertThrows(GuardrailException.class, () -> guard.passAll(new CityScope("emea", "munich")));
        assertThrows(GuardrailException.class, () -> guard.passAll(new RegionScope("apac")));
    }

    @Test
    void guardRunsEveryMemberAndAnyOneBlocks() throws Exception {
        ScopeGuard guard = new ScopeGuard(new TenantScope("t1"), new OtherScope("a"));
        guard.passAll(new TenantScope("t1"));
        guard.passAll(new OtherScope("a"));
        assertThrows(GuardrailException.class, () -> guard.passAll(new TenantScope("t2")));
        assertThrows(GuardrailException.class, () -> guard.passAll(new OtherScope("b")));
    }

    @Test
    void compositeCandidateIsFlattenedSoEachAxisIsJudged() {
        ScopeGuard guard = new ScopeGuard(new TenantScope("t1"));
        CompositeScope dualAxis = new CompositeScope(new OtherScope("a"), new TenantScope("t2"));
        assertThrows(GuardrailException.class, () -> guard.passAll(dualAxis));
    }

    @Test
    void compositeMemberJudgesByDelegation() {
        ScopeGuard guard = new ScopeGuard(new CompositeScope(new TenantScope("t1"), new OtherScope("a")));
        assertThrows(GuardrailException.class, () -> guard.passAll(new TenantScope("t2")));
        assertThrows(GuardrailException.class, () -> guard.passAll(new OtherScope("b")));
    }

    @Test
    void mergeDedupsByValueEqualityAndNeverMutates() {
        ScopeGuard a = new ScopeGuard(new TenantScope("t1"));
        ScopeGuard b = new ScopeGuard(new TenantScope("t1"), new OtherScope("a"));
        ScopeGuard merged = a.merge(b);
        assertEquals(2, merged.getScopes().size(), "equal members must collapse");
        assertEquals(1, a.getScopes().size());
        assertEquals(2, b.getScopes().size());
        ScopeGuard remerged = merged.merge(a).merge(b);
        assertEquals(2, remerged.getScopes().size(), "re-merging must be a non-event");
    }

    @Test
    void guardMemberListIsUnmodifiable() {
        ScopeGuard guard = new ScopeGuard(new TenantScope("t1"));
        assertThrows(UnsupportedOperationException.class, () -> guard.getScopes().add(new OtherScope("x")));
    }

    @Test
    void nestedCompositesFlattenOnConstruction() {
        CompositeScope nested = new CompositeScope(new CompositeScope(new TenantScope("t1"), new OtherScope("a")), new OtherScope("b"));
        assertEquals(3, nested.getComponents().size());
        assertTrue(nested.getComponents().stream().noneMatch(s -> s instanceof CompositeScope));
    }

    @Test
    void theRefusalNamesTheBindingAndTheClaim() {
        GuardrailException e = assertThrows(GuardrailException.class,
                () -> new TenantScope("t1").pass(new TenantScope("t2")));
        assertEquals("Scope mismatch: this flow is bound to TenantScope[tenantId=t1] but the input names TenantScope[tenantId=t2]",
                e.getMessage(), "the reason is the two values, legible to the model");
    }

    @Test
    void theTenantMarkerProducesTheTenantScope() {
        TenantScoped carrier = () -> "acme";
        assertEquals(new TenantScope("acme"), carrier.scope(), "implementing the marker is the whole declaration");
    }

    @Test
    void readOnlyIsOneBindingByTypeAndJudgesNobody() throws Exception {
        assertEquals(new ReadOnlyScope(), new ReadOnlyScope());
        assertEquals(new ReadOnlyScope().hashCode(), new ReadOnlyScope().hashCode());
        assertEquals("ReadOnlyScope", new ReadOnlyScope().toString());
        assertEquals(1, new ScopeGuard(new ReadOnlyScope(), new ReadOnlyScope()).getScopes().size(), "two read-only bindings are the same binding");
        new ReadOnlyScope().pass(new ReadOnlyScope());
        new ReadOnlyScope().pass(new TenantScope("t1"));
        new ScopeGuard(new ReadOnlyScope()).passAll(new TenantScope("t9"));
    }

    @Test
    void aScopeAloneDoesNotLookInsideACompositeCandidateTheGuardDoes() throws Exception {
        CompositeScope drifting = new CompositeScope(new OtherScope("a"), new TenantScope("t2"));
        new TenantScope("t1").pass(drifting);
        assertThrows(GuardrailException.class, () -> new ScopeGuard(new TenantScope("t1")).passAll(drifting),
                "the guard flattens a composite candidate so each axis is judged by the members that recognize it");
    }

    @Test
    void compositeEqualityIsByComponents() {
        CompositeScope a = new CompositeScope(new TenantScope("t1"), new OtherScope("a"));
        CompositeScope same = new CompositeScope(List.of(new TenantScope("t1"), new OtherScope("a")));
        CompositeScope reordered = new CompositeScope(new OtherScope("a"), new TenantScope("t1"));
        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, reordered, "components are an ordered list");
        assertEquals("CompositeScope[TenantScope[tenantId=t1], OtherScope[value=a]]", a.toString());
    }

    @Test
    void guardConstructorsDedupWhicheverFormIsUsed() {
        TenantScope t1 = new TenantScope("t1");
        assertEquals(2, new ScopeGuard(List.of(t1, t1, new OtherScope("a"))).getScopes().size());
        assertEquals(2, new ScopeGuard(t1, new TenantScope("t1"), new OtherScope("a")).getScopes().size(), "value-equal members collapse");
    }
}
