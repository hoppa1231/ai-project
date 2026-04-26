CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TYPE user_status AS ENUM ('ACTIVE', 'DISABLED', 'DELETED');
CREATE TYPE device_status AS ENUM ('ENABLED', 'DISABLED', 'REVOKED');
CREATE TYPE node_status AS ENUM ('ACTIVE', 'DRAINING', 'DISABLED');
CREATE TYPE node_health AS ENUM ('HEALTHY', 'UNHEALTHY', 'UNKNOWN');
CREATE TYPE client_status AS ENUM ('ACTIVE', 'REVOKED', 'EXPIRED');
CREATE TYPE config_status AS ENUM ('PROVISIONING', 'ISSUED', 'REVOKING', 'REVOKED', 'EXPIRED', 'FAILED');
CREATE TYPE policy_status AS ENUM ('ACTIVE', 'DISABLED');
CREATE TYPE default_route AS ENUM ('VPN', 'DIRECT');

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email CITEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
  status user_status NOT NULL DEFAULT 'ACTIVE',
  last_login_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE devices (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_fingerprint_hash CHAR(64) NOT NULL CHECK (device_fingerprint_hash ~ '^[0-9a-f]{64}$'),
  device_name TEXT NOT NULL,
  platform TEXT NOT NULL,
  app_version TEXT,
  public_key TEXT,
  status device_status NOT NULL DEFAULT 'ENABLED',
  bound_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_devices_user_fingerprint UNIQUE (user_id, device_fingerprint_hash),
  CONSTRAINT uq_devices_id_user UNIQUE (id, user_id)
);

CREATE TABLE nodes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name TEXT NOT NULL UNIQUE,
  region TEXT NOT NULL,
  country_code CHAR(2) NOT NULL,
  hostname TEXT NOT NULL,
  public_address TEXT NOT NULL,
  public_port INTEGER NOT NULL CHECK (public_port BETWEEN 1 AND 65535),
  api_host TEXT NOT NULL,
  api_port INTEGER NOT NULL CHECK (api_port BETWEEN 1 AND 65535),
  inbound_tag TEXT NOT NULL,
  reality_server_name TEXT NOT NULL,
  reality_public_key TEXT NOT NULL,
  reality_short_id VARCHAR(16) NOT NULL DEFAULT '' CHECK (reality_short_id ~ '^[0-9a-fA-F]{0,16}$' AND length(reality_short_id) % 2 = 0),
  reality_fingerprint TEXT NOT NULL DEFAULT 'chrome',
  reality_alpn TEXT[] NOT NULL DEFAULT ARRAY['h2', 'http/1.1']::TEXT[],
  weight INTEGER NOT NULL DEFAULT 100 CHECK (weight > 0),
  max_clients INTEGER NOT NULL DEFAULT 10000 CHECK (max_clients > 0),
  status node_status NOT NULL DEFAULT 'ACTIVE',
  health node_health NOT NULL DEFAULT 'UNKNOWN',
  health_message TEXT,
  latency_ms INTEGER,
  last_health_check_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_nodes_api_endpoint UNIQUE (api_host, api_port),
  CONSTRAINT uq_nodes_public_endpoint UNIQUE (hostname, public_port)
);

CREATE TABLE policies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
  default_route default_route NOT NULL DEFAULT 'VPN',
  include_apps TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
  exclude_apps TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
  include_domains TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
  exclude_domains TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
  policy_hash CHAR(64) NOT NULL CHECK (policy_hash ~ '^[0-9a-f]{64}$'),
  status policy_status NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE clients (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id UUID NOT NULL,
  node_id UUID NOT NULL REFERENCES nodes(id) ON DELETE RESTRICT,
  xray_email TEXT NOT NULL,
  vless_uuid UUID NOT NULL UNIQUE,
  flow TEXT,
  status client_status NOT NULL DEFAULT 'ACTIVE',
  expires_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  revoke_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_clients_device_owner FOREIGN KEY (device_id, user_id) REFERENCES devices(id, user_id) ON DELETE CASCADE,
  CONSTRAINT uq_clients_node_email UNIQUE (node_id, xray_email),
  CONSTRAINT uq_clients_id_user UNIQUE (id, user_id)
);

CREATE TABLE issued_configs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id UUID NOT NULL,
  node_id UUID NOT NULL REFERENCES nodes(id) ON DELETE RESTRICT,
  client_id UUID,
  idempotency_key TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
  status config_status NOT NULL DEFAULT 'PROVISIONING',
  config_hash CHAR(64) CHECK (config_hash ~ '^[0-9a-f]{64}$'),
  vless_uri TEXT,
  config_json JSONB,
  issued_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  error_code TEXT,
  error_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_issued_configs_device_owner FOREIGN KEY (device_id, user_id) REFERENCES devices(id, user_id) ON DELETE CASCADE,
  CONSTRAINT fk_issued_configs_client_owner FOREIGN KEY (client_id, user_id) REFERENCES clients(id, user_id) ON DELETE SET NULL,
  CONSTRAINT uq_issue_idempotency UNIQUE (user_id, idempotency_key),
  CONSTRAINT chk_issue_expiry CHECK (expires_at > created_at)
);

CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id UUID NOT NULL,
  jti UUID NOT NULL UNIQUE,
  token_hash CHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  replaced_by_jti UUID REFERENCES refresh_tokens(jti),
  ip INET,
  user_agent TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used_at TIMESTAMPTZ,
  CONSTRAINT fk_refresh_tokens_device_owner FOREIGN KEY (device_id, user_id) REFERENCES devices(id, user_id) ON DELETE CASCADE,
  CONSTRAINT chk_refresh_expiry CHECK (expires_at > created_at)
);

CREATE TABLE audit_log (
  id BIGSERIAL PRIMARY KEY,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  actor_device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
  action TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id UUID,
  request_id UUID,
  ip INET,
  user_agent TEXT,
  success BOOLEAN NOT NULL,
  details JSONB NOT NULL DEFAULT '{}'::JSONB
);

CREATE TABLE traffic_stats (
  id BIGSERIAL PRIMARY KEY,
  client_id UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
  node_id UUID NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
  period_start TIMESTAMPTZ NOT NULL,
  period_end TIMESTAMPTZ NOT NULL,
  uplink_bytes BIGINT NOT NULL DEFAULT 0 CHECK (uplink_bytes >= 0),
  downlink_bytes BIGINT NOT NULL DEFAULT 0 CHECK (downlink_bytes >= 0),
  source TEXT NOT NULL DEFAULT 'XRAY',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_traffic_stats_window UNIQUE (client_id, period_start, period_end),
  CONSTRAINT chk_traffic_window CHECK (period_end > period_start)
);

CREATE INDEX idx_devices_user_status ON devices(user_id, status);
CREATE INDEX idx_nodes_region_status ON nodes(region, status, health);
CREATE INDEX idx_clients_user_status ON clients(user_id, status);
CREATE INDEX idx_clients_node_status ON clients(node_id, status);
CREATE INDEX idx_issued_configs_user_status ON issued_configs(user_id, status);
CREATE INDEX idx_issued_configs_device_status ON issued_configs(device_id, status);
CREATE INDEX idx_refresh_tokens_user_device_active ON refresh_tokens(user_id, device_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_audit_log_occurred_at ON audit_log(occurred_at DESC);
CREATE INDEX idx_audit_log_actor_user ON audit_log(actor_user_id, occurred_at DESC);
CREATE INDEX idx_audit_log_action ON audit_log(action, occurred_at DESC);

CREATE UNIQUE INDEX uq_clients_active_per_device ON clients(device_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_issued_configs_active_per_device ON issued_configs(device_id) WHERE status IN ('PROVISIONING', 'ISSUED', 'REVOKING');

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_devices_updated_at BEFORE UPDATE ON devices FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_nodes_updated_at BEFORE UPDATE ON nodes FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_policies_updated_at BEFORE UPDATE ON policies FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_clients_updated_at BEFORE UPDATE ON clients FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_issued_configs_updated_at BEFORE UPDATE ON issued_configs FOR EACH ROW EXECUTE FUNCTION set_updated_at();
