# ALLERION / ServerMonitor API expansion contract

This document defines the backend additions needed before the Android client can expose advanced infrastructure features safely. Existing endpoints remain unchanged.

## Common requirements

- All endpoints require the existing X-Token authentication header.
- Return JSON with stable field names and explicit units.
- Use Unix epoch milliseconds for event timestamps.
- Return HTTP 404 for unsupported capabilities; clients must hide or mark unsupported panels rather than fabricate values.
- Include an error object with stable error code on failures.
- Add a capability document so clients can feature-detect without probing every endpoint.

## Capability discovery

GET /api/capabilities

Example response:

    {
      "api_version": 2,
      "features": {
        "containers": true,
        "processes": true,
        "services": true,
        "network_inventory": true,
        "network_diagnostics": true,
        "energy_breakdown": true,
        "webhooks": false
      }
    }

## Docker and services

- GET /api/containers — container ID, name, image, state, health, CPU %, memory bytes, restart count and started-at timestamp.
- GET /api/containers/{id}/stats?range=1h|24h|7d — time-series CPU and memory metrics.
- GET /api/services — configured service checks with name, URL/host, expected status, last status, response time and last checked timestamp.
- GET /api/processes?limit=50&sort=cpu|memory — bounded process list with PID, name, CPU %, memory bytes and user. Never return command-line arguments or environment variables by default.

## Network inventory and diagnostics

- GET /api/network/devices — LAN device inventory with stable local ID, IP, MAC, hostname, vendor (when known), first-seen and last-seen timestamps.
- GET /api/network/diagnostics?target={host} — DNS resolution, TCP connect duration, HTTP response duration, packet loss when measured by a supported probe, and test timestamp.
- GET /api/network/topology — discovered device links only when the collector has evidence; do not infer physical topology from IP ranges.

Network scans must be opt-in, limited to private address ranges, rate-limited and visible in the UI. Do not scan arbitrary public IP ranges.

## Energy intelligence

- GET /api/energy?range=7d|30d|12m — daily/hourly kWh totals, tariff/currency, cost totals and coverage metadata.
- GET /api/energy/breakdown?range=30d — measured or explicitly estimated usage by source/device.
- GET /api/energy/forecast?days=30 — forecast values plus method, confidence/coverage and assumptions.

Every energy value must identify whether it is measured, estimated from CPU/RAPL, or estimated from a configured base-watt value.

## Integrations

- GET /api/integrations — configured integration names and enabled state only; never return secrets.
- POST /api/integrations/webhook/test — test a user-configured HTTPS webhook.
- POST /api/integrations/webhook — save endpoint and secret server-side; redact secret from all reads and logs.

Telegram credentials and webhook secrets must be stored on the backend, not embedded in the Android APK. Support test delivery and disable/delete actions.

## Compatibility and implementation order

1. Add /api/capabilities and stable API versioning.
2. Add Docker/container and service collectors.
3. Add LAN inventory and diagnostics with explicit opt-in.
4. Extend energy breakdown and forecast endpoints.
5. Add backend-managed webhook delivery.

The Android app currently supports the original stats/history/energy/power APIs and gracefully treats absent optional data as unavailable. Implementing these endpoints in the ServerMonitor service is required before corresponding advanced panels can be enabled.