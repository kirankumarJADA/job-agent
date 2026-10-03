# syntax=docker/dockerfile:1
# Production Playwright worker container (Phase 16).
#
# Base image is pinned to the SAME major as the @playwright/test dependency
# in package.json (^1.63) - the bundled browser build must match the driver,
# otherwise launch fails with a version mismatch. The noble variant carries
# every system library Chromium needs on Ubuntu 24.04.
#
# The worker runs as the non-root `pwuser` baked into the image. Chromium
# launch flags (--no-sandbox, --disable-dev-shm-usage) are set in
# src/browser_worker.js; combined with the non-root user this is the safe
# configuration for Render/OCI memory-constrained instances.
FROM mcr.microsoft.com/playwright:v1.63.0-noble

WORKDIR /app

# Worker state (durable step ledger) and downloaded artifacts both live under
# /data. Mount a volume there owned by UID 1000 (pwuser) - e.g.
#   docker run -v worker-data:/data
# On Render, a persistent disk must be mounted at /data with the default uid.
COPY package.json package-lock.json ./
RUN npm ci

COPY src ./src
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh \
    && mkdir -p /data \
    && chown -R pwuser:pwuser /app /data

USER pwuser

ENV NODE_ENV=production \
    WORKER_STATE_PATH=/data/worker-state.json \
    WORKER_ARTIFACT_DIR=/data/artifacts \
    WORKER_POLL_INTERVAL_MS=10000

# Deployment wiring (Render worker service / OCI container). Point these at
# the deployed backend; the worker claims plans, heartbeats its lease, posts
# worker events and reports outcomes:
#   WORKER_POLL_URL       https://<backend>/api/v1/automation/plans/claim-next
#   WORKER_HEARTBEAT_URL  https://<backend>/api/v1/automation/plans/{id}/heartbeat
#   WORKER_COMPLETE_URL   https://<backend>/api/v1/automation/plans/{id}/complete
#   WORKER_EVENT_URL      https://<backend>/api/v1/automation/events
#   WORKER_EVENT_TOKEN    same value as the backend's WORKER_EVENT_TOKEN
# WORKER_POLL_URL is also the base the worker derives its package/artifact
# URLs from, so it must include the /api/v1/automation prefix as above.

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["node", "src/worker.js"]
