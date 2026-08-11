# Deployment

`src/deployment` is the canonical product space for **standalone bare-metal and virtual-machine deployment** roles, topologies and traits. Product installation, upgrade, repair, rollback and service-management assets must target those environments directly and must not require Docker, Docker Compose, Podman or another container runtime.

Containerized developer environments are engineering tooling only. They live outside the canonical product source and release inventory (for example under the Git-ignored `.infranexum-dev/` workspace) and cannot become a production deployment dependency.

Certified standalone deployment assets will be added incrementally without creating a fourth application; deployable roles remain Server, Web and Agent.
