#!/usr/bin/env python3
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import random
import string
import time
import uuid
from datetime import datetime, timezone
from typing import Any

import requests


BASE_URL_DEFAULT = "https://tech-supp-test.ru"

DEVICE_MODELS = [
    "Pixel 8",
    "Pixel 8 Pro",
    "Pixel 7a",
    "Samsung Galaxy S23",
    "Samsung Galaxy S24",
    "Xiaomi 13T",
    "Xiaomi Redmi Note 12",
    "OnePlus 11",
    "Nothing Phone 2",
    "Honor 90",
]

ANDROID_VERSIONS = ["13", "14", "15"]
APP_VERSIONS = ["0.1.0", "0.1.1", "0.2.0", "0.2.1", "0.3.0"]

REGIONS = [
    {"region": "ru", "countryCode": "RU", "city": "Moscow", "prefix": "msk"},
    {"region": "de", "countryCode": "DE", "city": "Frankfurt", "prefix": "fra"},
    {"region": "nl", "countryCode": "NL", "city": "Amsterdam", "prefix": "ams"},
    {"region": "fi", "countryCode": "FI", "city": "Helsinki", "prefix": "hel"},
    {"region": "tr", "countryCode": "TR", "city": "Istanbul", "prefix": "ist"},
    {"region": "kz", "countryCode": "KZ", "city": "Almaty", "prefix": "ala"},
]

REALITY_SNI = [
    "www.microsoft.com",
    "www.google.com",
    "cloudflare.com",
    "www.cloudflare.com",
    "www.apple.com",
    "www.amazon.com",
]

PACKAGE_NAMES = [
    "com.android.chrome",
    "org.telegram.messenger",
    "com.whatsapp",
    "com.instagram.android",
    "com.google.android.youtube",
    "com.discord",
    "com.spotify.music",
]

DOMAINS = [
    "google.com",
    "youtube.com",
    "telegram.org",
    "cloudflare.com",
    "microsoft.com",
    "github.com",
    "openai.com",
]


def now_run_id() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")


def random_token(length: int = 12) -> str:
    alphabet = string.ascii_lowercase + string.digits
    return "".join(random.choice(alphabet) for _ in range(length))


def random_password() -> str:
    return f"Qa-{random_token(10)}-{random.randint(1000, 9999)}!"


def random_b64url_key(byte_len: int = 32) -> str:
    raw = os.urandom(byte_len)
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def random_short_id() -> str:
    return os.urandom(4).hex()


def fake_public_ip(i: int) -> str:
    # TEST-NET ranges, safe for documentation/testing.
    # 203.0.113.0/24 and 198.51.100.0/24 are reserved example networks.
    if i % 2 == 0:
        return f"203.0.113.{10 + (i % 200)}"
    return f"198.51.100.{10 + (i % 200)}"


def stable_fingerprint(seed: str) -> str:
    digest = hashlib.sha256(seed.encode("utf-8")).hexdigest()
    return f"android-{digest}"


def build_device_payload(run_id: str, index: int) -> dict[str, Any]:
    model = random.choice(DEVICE_MODELS)
    android_version = random.choice(ANDROID_VERSIONS)

    return {
        "deviceFingerprint": stable_fingerprint(f"{run_id}-{index}-{model}-{uuid.uuid4()}"),
        "deviceName": f"{model} Android {android_version}",
        "platform": "android",
        "appVersion": random.choice(APP_VERSIONS),
        "publicKey": random_b64url_key(),
    }


def build_register_payload(run_id: str, index: int) -> dict[str, Any]:
    return {
        "email": f"qa.user.{run_id}.{index:04d}@example.com",
        "password": random_password(),
    }


def build_issue_config_payload(device_id: str) -> dict[str, Any]:
    return {
        "deviceId": device_id,
        "region": random.choice(REGIONS)["region"],
        "ttlHours": random.choice([6, 12, 24, 48, 72, 168]),
        "forceRotate": random.choice([False, False, False, True]),
    }


def build_node_payload(run_id: str, index: int) -> dict[str, Any]:
    region = REGIONS[index % len(REGIONS)]
    node_num = index + 1

    return {
        "name": f"{region['city']} Edge {node_num:02d}",
        "region": region["region"],
        "countryCode": region["countryCode"],
        "hostname": f"{region['prefix']}-edge-{node_num:02d}.vpn.example.com",
        "publicAddress": fake_public_ip(index),
        "publicPort": random.choice([443, 8443, 9443]),
        "apiHost": "127.0.0.1",
        "apiPort": 10085 + index,
        "inboundTag": f"{region['prefix']}-vless-{node_num:02d}",
        "realityServerName": random.choice(REALITY_SNI),
        "realityPublicKey": random_b64url_key(),
        "realityShortId": random_short_id(),
        "realityFingerprint": "chrome",
        "realityAlpn": random.choice([["h2", "http/1.1"], ["http/1.1"], ["h2"]]),
        "weight": random.choice([50, 75, 100, 125, 150]),
        "maxClients": random.choice([500, 750, 1000, 1500, 2000]),
    }


def build_quota_payload(email: str | None = None, user_id: str | None = None) -> dict[str, Any]:
    payload = {
        "gb": random.choice([5, 10, 20, 30, 50, 100]),
        "source": "qa_seed",
        "externalRef": f"seed-{uuid.uuid4()}",
    }

    if user_id:
        payload["userId"] = user_id
    elif email:
        payload["email"] = email

    return payload


def build_policy_payload(if_version: int = 1) -> dict[str, Any]:
    include_apps = random.sample(PACKAGE_NAMES, k=random.randint(1, 3))
    exclude_domains = random.sample(DOMAINS, k=random.randint(1, 3))

    return {
        "ifVersion": if_version,
        "defaultRoute": random.choice(["VPN", "DIRECT"]),
        "includeApps": include_apps,
        "excludeApps": [],
        "includeDomains": [],
        "excludeDomains": exclude_domains,
    }


class ApiClient:
    def __init__(self, base_url: str, timeout: int = 30, dry_run: bool = False):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.dry_run = dry_run
        self.session = requests.Session()
        self.session.headers.update({
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": "vpn-control-plane-seeder/1.0",
        })

    def request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        token: str | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> dict[str, Any] | None:
        url = f"{self.base_url}{path}"

        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if extra_headers:
            headers.update(extra_headers)

        print(f"\n{method.upper()} {path}")
        if payload is not None:
            print(json.dumps(payload, ensure_ascii=False, indent=2))

        if self.dry_run:
            return {"dryRun": True}

        response = self.session.request(
            method=method.upper(),
            url=url,
            json=payload,
            headers=headers,
            timeout=self.timeout,
        )

        try:
            data = response.json()
        except Exception:
            data = {"raw": response.text}

        print(f"HTTP {response.status_code}")
        print(json.dumps(data, ensure_ascii=False, indent=2))

        if response.status_code < 200 or response.status_code >= 300:
            raise RuntimeError(f"{method.upper()} {path} failed with HTTP {response.status_code}")

        return data


def create_guest(client: ApiClient, run_id: str, index: int, issue_vpn: bool) -> dict[str, Any]:
    device_payload = build_device_payload(run_id, index)

    auth = client.request("POST", "/auth/guest", device_payload)

    result = {
        "type": "guest",
        "devicePayload": device_payload,
        "auth": auth,
        "vpnConfig": None,
    }

    access_token = auth.get("accessToken") if auth else None
    device_id = auth.get("deviceId") if auth else None

    if issue_vpn and access_token and device_id:
        issue_payload = build_issue_config_payload(device_id)
        result["vpnConfig"] = client.request(
            "POST",
            "/vpn/issue",
            issue_payload,
            token=access_token,
            extra_headers={"Idempotency-Key": str(uuid.uuid4())},
        )

    return result


def create_registered_user(client: ApiClient, run_id: str, index: int, issue_vpn: bool) -> dict[str, Any]:
    register_payload = build_register_payload(run_id, index)

    auth = client.request("POST", "/auth/register", register_payload)

    access_token = auth.get("accessToken") if auth else None

    result = {
        "type": "registered_user",
        "email": register_payload["email"],
        "password": register_payload["password"],
        "auth": auth,
        "boundDevice": None,
        "vpnConfig": None,
    }

    if not access_token:
        return result

    device_payload = build_device_payload(run_id, index)
    bound_device = client.request(
        "POST",
        "/devices/bind",
        device_payload,
        token=access_token,
    )
    result["boundDevice"] = bound_device

    # /devices/bind может вернуть новый accessToken.
    access_token = bound_device.get("accessToken") or access_token
    device_id = bound_device.get("deviceId")

    if issue_vpn and device_id:
        issue_payload = build_issue_config_payload(device_id)
        result["vpnConfig"] = client.request(
            "POST",
            "/vpn/issue",
            issue_payload,
            token=access_token,
            extra_headers={"Idempotency-Key": str(uuid.uuid4())},
        )

    return result


def create_admin_node(client: ApiClient, run_id: str, index: int, admin_token: str) -> dict[str, Any]:
    payload = build_node_payload(run_id, index)
    response = client.request("POST", "/admin/nodes", payload, token=admin_token)

    return {
        "type": "admin_node",
        "request": payload,
        "response": response,
    }


def login_admin(client: ApiClient, email: str, password: str) -> str:
    auth = client.request("POST", "/auth/login", {"email": email, "password": password})
    token = auth.get("accessToken") if auth else None
    if not token:
        raise RuntimeError("Admin login did not return accessToken")
    return token


def grant_quota_to_email(client: ApiClient, email: str, admin_token: str) -> dict[str, Any]:
    payload = build_quota_payload(email=email)
    response = client.request("POST", "/admin/quota/grant", payload, token=admin_token)

    return {
        "type": "quota_grant",
        "email": email,
        "request": payload,
        "response": response,
    }


def save_jsonl(path: str, record: dict[str, Any]) -> None:
    with open(path, "a", encoding="utf-8") as file:
        file.write(json.dumps(record, ensure_ascii=False) + "\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()

    parser.add_argument("--base-url", default=BASE_URL_DEFAULT)

    parser.add_argument("--guests", type=int, default=0)
    parser.add_argument("--users", type=int, default=0)
    parser.add_argument("--nodes", type=int, default=0)

    parser.add_argument("--issue-vpn", action="store_true")
    parser.add_argument("--grant-quota", action="store_true")

    parser.add_argument("--admin-token", default=os.getenv("ADMIN_TOKEN"))
    parser.add_argument("--admin-email", default=os.getenv("ADMIN_EMAIL", "admin@securevpn.local"))
    parser.add_argument("--admin-password", default=os.getenv("ADMIN_PASSWORD", "Admin12345!"))

    parser.add_argument("--delay", type=float, default=0.3)
    parser.add_argument("--dry-run", action="store_true")

    parser.add_argument("--out", default="seed_results.jsonl")

    return parser.parse_args()


def main() -> None:
    args = parse_args()

    run_id = now_run_id()
    client = ApiClient(
        base_url=args.base_url,
        dry_run=args.dry_run,
    )

    if (args.nodes > 0 or args.grant_quota) and not args.admin_token:
        if args.dry_run:
            args.admin_token = "dry-run-admin-token"
        else:
            args.admin_token = login_admin(client, args.admin_email, args.admin_password)

    created_user_emails: list[str] = []

    print(f"Run ID: {run_id}")
    print(f"Output: {args.out}")
    print(f"Dry run: {args.dry_run}")

    for i in range(args.guests):
        try:
            result = create_guest(client, run_id, i, issue_vpn=args.issue_vpn)
            save_jsonl(args.out, result)
        except Exception as exc:
            save_jsonl(args.out, {
                "type": "error",
                "scope": "guest",
                "index": i,
                "error": str(exc),
            })
            print(f"ERROR guest #{i}: {exc}")

        time.sleep(args.delay)

    for i in range(args.users):
        try:
            result = create_registered_user(client, run_id, i, issue_vpn=args.issue_vpn)
            save_jsonl(args.out, result)

            email = result.get("email")
            if email:
                created_user_emails.append(email)

        except Exception as exc:
            save_jsonl(args.out, {
                "type": "error",
                "scope": "registered_user",
                "index": i,
                "error": str(exc),
            })
            print(f"ERROR user #{i}: {exc}")

        time.sleep(args.delay)

    for i in range(args.nodes):
        try:
            result = create_admin_node(client, run_id, i, args.admin_token)
            save_jsonl(args.out, result)
        except Exception as exc:
            save_jsonl(args.out, {
                "type": "error",
                "scope": "admin_node",
                "index": i,
                "error": str(exc),
            })
            print(f"ERROR node #{i}: {exc}")

        time.sleep(args.delay)

    if args.grant_quota:
        for email in created_user_emails:
            try:
                result = grant_quota_to_email(client, email, args.admin_token)
                save_jsonl(args.out, result)
            except Exception as exc:
                save_jsonl(args.out, {
                    "type": "error",
                    "scope": "quota_grant",
                    "email": email,
                    "error": str(exc),
                })
                print(f"ERROR quota for {email}: {exc}")

            time.sleep(args.delay)

    print("\nDone.")
    print(f"Results saved to: {args.out}")


if __name__ == "__main__":
    main()
