# ALLERION / ServerMonitor API v2

The companion backend update is supplied as `ALLERION_ServerMonitor_v2.zip`. This document describes the implemented endpoints and the remaining limitations. Existing v1 endpoints remain compatible.

## Authentication and capability discovery

- All `/api/*` endpoints require the `X-Token` header.
- Query-string token authentication is disabled to avoid leaking bearer credentials through URL logs and browser history.
- `TOKEN` is required at startup unless the isolated-test-only `ALLOW_INSECURE_LOCAL=1` override is explicitly set.
- `GET /api/capabilities` reports API version and which optional collectors are configured.

## Host and container inventory

- `GET /api/processes?limit=30` returns a bounded list with PID, process name, username, CPU percentage and resident memory bytes. Command-line arguments and environment variables are not exposed.
- `GET /api/containers` returns Docker container ID, name, image, state and status.
- `GET /api/containers/{id}/stats` returns point-in-time Docker CPU and memory usage.
- Docker access uses a loopback-bound Docker socket proxy with POST disabled; port 2375 must never be exposed to the LAN or internet.
- `GET /api/services` checks admin-configured URLs from `SERVICE_CHECKS`. The backend caches results briefly and returns status and latency.

## LAN diagnostics

- `GET /api/network/devices` reads the passive ARP / neighbor cache when `ENABLE_LAN_DISCOVERY=1`.
- `GET /api/network/topology` returns passive inventory nodes. It does not invent physical links from IP addresses.
- `GET /api/network/diagnostics?target=192.168.1.10` tests TCP connect reachability on ports 443, 80, 22 and 8080. Only private IPv4 addresses are accepted.
- The current diagnostic probe does not measure ICMP packet loss and does not actively scan the subnet.

## Energy intelligence

- `GET /api/energy` preserves the existing daily kWh totals.
- `GET /api/energy/breakdown` attributes new energy intervals to the active source (smart plug or CPU/RAPL estimate). Existing historical totals are retained but cannot be attributed retroactively.
- `GET /api/energy/forecast?days=30` uses the arithmetic mean of at least three recorded daily totals. It is an estimate, not a measured prediction.

## Notifications and integrations

- `GET /api/integrations` reports whether webhook and Telegram destinations are configured; it never returns credentials.
- `POST /api/integrations/webhook/test` sends a test event to an HTTPS URL configured with `ALERT_WEBHOOK_URL`; optional `ALERT_WEBHOOK_SECRET` is sent as `X-Webhook-Secret`.
- `POST /api/integrations/telegram/test` sends a test message using backend-only `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`.
- CPU, RAM, temperature and disk threshold crossings trigger notifications, with a recovery event when the metric falls below its threshold.
- Default thresholds are CPU 90%, RAM 90%, temperature 85 °C and disk 92%; configure with `ALERT_CPU_THRESHOLD`, `ALERT_RAM_THRESHOLD`, `ALERT_TEMP_THRESHOLD` and `ALERT_DISK_THRESHOLD`.

Secrets are configured only on the backend. The Android app cannot read or write webhook URLs, webhook secrets or Telegram bot credentials.

## Deployment and security notes

1. Back up the existing `data/` directory before replacing the service; it contains power configuration and energy history.
2. Copy `.env.example` to `.env` and set a new long random `TOKEN`. Never upload the real `.env` or `credentials.env`.
3. Run `docker compose up -d --build`.
4. Keep the API on a trusted LAN or place it behind HTTPS for remote access. Do not expose the Docker proxy port 2375.
5. Power configuration is written with restrictive file permissions. Continue to keep the persistent `data/` directory private.

The Android client detects optional features through `/api/capabilities` and only renders panels when the backend reports support. Features requiring additional collectors beyond those listed above remain future backend work.
