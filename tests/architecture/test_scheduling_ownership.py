from __future__ import annotations

import re
import unittest
from pathlib import Path


class ServerSchedulingOwnershipTest(unittest.TestCase):
    """Prevent Spring scheduling from falling back to an implicit local executor."""

    ROOT = Path(__file__).resolve().parents[2]
    SERVER = ROOT / "src/applications/server/main/io/infranexum/server"

    def test_platform_owns_canonical_spring_task_scheduler_name(self) -> None:
        platform = (self.SERVER / "configuration/PlatformSchedulingConfiguration.java").read_text(encoding="utf-8")
        workers = (self.SERVER / "workers/WorkerRuntimeConfiguration.java").read_text(encoding="utf-8")

        self.assertIn('@Bean("taskScheduler")', platform)
        self.assertIn("ThreadPoolTaskScheduler taskScheduler(", platform)
        self.assertIn("TaskScheduler workerTaskScheduler(", workers)
        self.assertNotIn("TaskScheduler taskScheduler(", workers)

    def test_no_other_production_bean_claims_task_scheduler_name(self) -> None:
        owners: list[str] = []
        for path in sorted(self.SERVER.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            if re.search(r'@Bean\("taskScheduler"\)', text):
                owners.append(str(path.relative_to(self.ROOT)))

        self.assertEqual(
            ["src/applications/server/main/io/infranexum/server/configuration/PlatformSchedulingConfiguration.java"],
            owners,
        )

    def test_scheduler_is_bounded_and_shutdown_aware(self) -> None:
        platform = (self.SERVER / "configuration/PlatformSchedulingConfiguration.java").read_text(encoding="utf-8")
        properties = (self.SERVER / "configuration/SchedulingRuntimeProperties.java").read_text(encoding="utf-8")

        self.assertIn("scheduler.setPoolSize(properties.poolSize())", platform)
        self.assertIn("scheduler.setAwaitTerminationMillis(properties.shutdownTimeout().toMillis())", platform)
        self.assertIn("scheduler.setRemoveOnCancelPolicy(true)", platform)
        self.assertIn("scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)", platform)
        self.assertIn('ConfigurationProperties(prefix = "infranexum.scheduling")', properties)


if __name__ == "__main__":
    unittest.main()
