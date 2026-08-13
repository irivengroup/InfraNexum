-- Intentionally non-restorative: ADR-0029 forbids recreating inter-bounded-context foreign keys.
-- alpha.0.68 remains schema-compatible because the weak-reference columns are unchanged.
SELECT 1;
