# VPN Node Agent

Small provisioning agent that runs on a VPN node and translates backend commands into 3x-ui API calls.

## Required Environment

```bash
AGENT_PORT=9090
AGENT_TOKEN=change-this-long-random-token
THREE_XUI_BASE_URL=http://127.0.0.1:2053
THREE_XUI_USERNAME=admin
THREE_XUI_PASSWORD=admin
THREE_XUI_INBOUND_ID=1
```

Optional:

```bash
THREE_XUI_INBOUND_MAP=msk-vless-01:1,backup-vless:2
THREE_XUI_2FA_CODE=123456
```

If 3x-ui uses a custom web base path, include it in `THREE_XUI_BASE_URL`, for example:

```bash
THREE_XUI_BASE_URL=http://127.0.0.1:2053/my-secret-path
```

## Backend Settings

The backend node row must point `api_host` and `api_port` to this agent, not directly to 3x-ui.

```bash
XRAY_MODE=agent
AGENT_SCHEME=http
AGENT_BASE_PATH=
AGENT_TOKEN=change-this-long-random-token
```

## Local Run

```bash
gradle run
```

## Health Check

```bash
curl -H "Authorization: Bearer $AGENT_TOKEN" http://127.0.0.1:9090/v1/health
```
