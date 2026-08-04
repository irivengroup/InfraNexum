# Activation operational integration

The independent temporal proof is stored outside the database using an atomic, fsync-backed file. Import writes the independent proof first, then commits manifest/state/database proof in one JDBC transaction. A database failure restores the previous independent proof. A failed compensation is fail-closed and requires operator intervention. This protocol does not claim distributed ACID atomicity.
