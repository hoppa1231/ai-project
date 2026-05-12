CREATE TABLE node_client_inventory (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  node_id UUID NOT NULL REFERENCES nodes(id) ON DELETE CASCADE,
  inbound_id INTEGER NOT NULL,
  inbound_remark TEXT NOT NULL DEFAULT '',
  inbound_tag TEXT NOT NULL DEFAULT '',
  xray_email TEXT NOT NULL,
  vless_uuid TEXT,
  flow TEXT,
  enabled BOOLEAN NOT NULL DEFAULT true,
  total_bytes BIGINT NOT NULL DEFAULT 0 CHECK (total_bytes >= 0),
  up_bytes BIGINT NOT NULL DEFAULT 0 CHECK (up_bytes >= 0),
  down_bytes BIGINT NOT NULL DEFAULT 0 CHECK (down_bytes >= 0),
  expiry_time BIGINT NOT NULL DEFAULT 0,
  limit_ip INTEGER NOT NULL DEFAULT 0,
  sub_id TEXT,
  last_synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_node_client_inventory_email UNIQUE (node_id, xray_email)
);

CREATE INDEX idx_node_client_inventory_node ON node_client_inventory(node_id, inbound_id);
CREATE INDEX idx_node_client_inventory_email ON node_client_inventory(xray_email);

CREATE TRIGGER trg_node_client_inventory_updated_at
BEFORE UPDATE ON node_client_inventory
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
