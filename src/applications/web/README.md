# Web

The Web composition root is executable and remains intentionally narrow: it serves immutable static assets, validated public runtime configuration, health probes and build metadata. It does not embed Server business logic.

## Visual baseline

The Web shell uses Bootstrap 5.x as its CSS framework and applies only the visual theme recovered from the predecessor source asset. The source is the historical `public/assets/bootstrap.css` theme blob (1,804 bytes; SHA-256 `07b9b698d639a8bd9b2ce758e51754be4d33ca03cb5a692cc566319f3cc9f1a9`), whose content is theme CSS rather than the Bootstrap framework. Its palette, typography, surfaces, focus treatment, dark mode and mobile breakpoint are adapted into `public/assets/infranexum-theme.css`. Bootstrap is vendored under `public/assets/vendor/` and is loaded first. The primary theme blue remains `#003D8F`.

No CDN or remotely hosted runtime asset is required. Bootstrap framework JavaScript is intentionally not shipped while the shell does not use interactive Bootstrap components. The vendored framework asset is versioned, SHA-256 pinned by tests and accompanied by its MIT license notice.

No predecessor template, HTML shell, JavaScript runtime or business component is copied. The InfraNexum markup remains its own shell and uses Bootstrap responsive primitives; `infranexum-theme.css` is strictly a visual overlay. The shell preserves responsive layout, visible keyboard focus, semantic structure, assistive-technology status announcements and light/dark presentation. Future React/TypeScript feature screens must use this Bootstrap/theme foundation rather than introducing a parallel styling system.

## PRO developer topology

The PRO developer topology runs two non-root Web nodes behind HAProxy. Raw node ports stay private to the Compose bridge; the stable Web router is published on loopback only. The browser-facing `apiBaseUrl` points to the separately published Server router so the existing Web-local `/api/v1/system/build` contract remains unambiguous.

Production packaging and the full PGM-05 user interface remain separate roadmap increments.
