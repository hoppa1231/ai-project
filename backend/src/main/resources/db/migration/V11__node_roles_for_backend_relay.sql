CREATE TYPE node_role AS ENUM ('ENTRY', 'EXIT', 'BOTH');

ALTER TABLE nodes
  ADD COLUMN node_role node_role NOT NULL DEFAULT 'EXIT';

CREATE INDEX idx_nodes_role_status_health
  ON nodes(node_role, status, health);
