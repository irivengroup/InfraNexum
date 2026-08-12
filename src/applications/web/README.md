# Web

The Web composition root is executable and remains intentionally narrow: it serves immutable static assets, validated public runtime configuration, health probes and build metadata. It does not embed Server business logic.

The PRO developer topology runs two non-root Web nodes behind HAProxy. Raw node ports stay private to the Compose bridge; the stable Web router is published on loopback only. The browser-facing `apiBaseUrl` points to the separately published Server router so the existing Web-local `/api/v1/system/build` contract remains unambiguous.

Production packaging and the full PGM-05 user interface remain separate roadmap increments.
