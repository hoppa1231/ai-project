CREATE TYPE config_route_mode AS ENUM ('SINGLE', 'CASCADE');
CREATE TYPE config_hop_role AS ENUM ('ENTRY', 'EXIT');
CREATE TYPE config_hop_status AS ENUM ('PROVISIONING', 'ACTIVE', 'REVOKED', 'FAILED');

ALTER TABLE issued_configs
  ADD COLUMN route_mode config_route_mode NOT NULL DEFAULT 'SINGLE';

DROP INDEX IF EXISTS uq_clients_active_per_device;

CREATE TABLE issued_config_hops (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  config_id UUID NOT NULL REFERENCES issued_configs(id) ON DELETE CASCADE,
  hop_index INTEGER NOT NULL CHECK (hop_index >= 0),
  role config_hop_role NOT NULL,
  node_id UUID NOT NULL REFERENCES nodes(id) ON DELETE RESTRICT,
  client_id UUID REFERENCES clients(id) ON DELETE SET NULL,
  vless_uuid UUID NOT NULL,
  flow TEXT,
  status config_hop_status NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at TIMESTAMPTZ,
  CONSTRAINT uq_issued_config_hop_index UNIQUE (config_id, hop_index),
  CONSTRAINT uq_issued_config_hop_client UNIQUE (config_id, client_id)
);

CREATE INDEX idx_issued_config_hops_config ON issued_config_hops(config_id, hop_index);
CREATE INDEX idx_issued_config_hops_node_status ON issued_config_hops(node_id, status);
