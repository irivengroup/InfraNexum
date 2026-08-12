from __future__ import annotations

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
CORE = ROOT / "src/components/core/workers/main/io/infranexum/core/workers"
SERVER = ROOT / "src/applications/server/main/io/infranexum/server/workers"
APPLICATION = ROOT / "src/applications/server/resources/application.yaml"


class WorkerObservabilityArchitectureTest(unittest.TestCase):
    """Keep PGM-02-E07 operational visibility explicit and low-cardinality."""

    def test_pool_snapshot_makes_partial_worker_loss_fail_closed(self) -> None:
        pool = (CORE / "TaskWorkerPool.java").read_text(encoding="utf-8")
        snapshot = (CORE / "WorkerPoolSnapshot.java").read_text(encoding="utf-8")
        self.assertIn("liveWorkers.incrementAndGet()", pool)
        self.assertIn("fatalLoopFailures.increment()", pool)
        self.assertIn("public WorkerPoolSnapshot snapshot()", pool)
        self.assertIn("liveWorkers == configuredConcurrency", snapshot)
        self.assertIn("storeReadyWorkers == configuredConcurrency", snapshot)
        self.assertIn("catch (TaskStoreUnavailableException unavailable)", pool)
        self.assertIn("storeUnavailableFailures.increment()", pool)
        self.assertIn("fatalLoopFailures == 0", snapshot)

    def test_workers_are_explicit_readiness_members(self) -> None:
        application = APPLICATION.read_text(encoding="utf-8")
        health = (SERVER / "WorkerHealthIndicator.java").read_text(encoding="utf-8")
        self.assertIn("include: readinessState,workers", application)
        self.assertIn("snapshot.ready() ? Health.up() : Health.down()", health)
        self.assertIn('withDetail("storeReadyWorkers"', health)
        self.assertIn('withDetail("storeUnavailableFailures"', health)
        self.assertIn('withDetail("fatalLoopFailures"', health)

    def test_worker_metrics_are_fixed_cardinality_and_exposed(self) -> None:
        application = APPLICATION.read_text(encoding="utf-8")
        metrics = (SERVER / "WorkerMetrics.java").read_text(encoding="utf-8")
        self.assertIn("include: health,info,metrics", application)
        for name in (
            "infranexum.workers.enabled",
            "infranexum.workers.ready",
            "infranexum.workers.capacity",
            "infranexum.workers.live",
            "infranexum.workers.store.ready",
            "infranexum.workers.store.ready.loops",
            "infranexum.workers.store.unavailable.failures",
            "infranexum.workers.active",
            "infranexum.workers.tasks.claimed",
            "infranexum.workers.loop.failures",
        ):
            self.assertIn(name, metrics)
        self.assertNotIn(".tag(\"task", metrics)
        self.assertNotIn("taskId", metrics)
        self.assertNotIn("workerId", metrics)


if __name__ == "__main__":
    unittest.main()
