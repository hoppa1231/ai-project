ALTER TABLE clients
  ADD COLUMN device_fingerprint_hash CHAR(64) CHECK (device_fingerprint_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE issued_configs
  ADD COLUMN device_fingerprint_hash CHAR(64) CHECK (device_fingerprint_hash ~ '^[0-9a-f]{64}$');

UPDATE clients c
SET device_fingerprint_hash = d.device_fingerprint_hash
FROM devices d
WHERE c.device_id = d.id
  AND c.user_id = d.user_id
  AND c.device_fingerprint_hash IS NULL;

UPDATE issued_configs ic
SET device_fingerprint_hash = d.device_fingerprint_hash
FROM devices d
WHERE ic.device_id = d.id
  AND ic.user_id = d.user_id
  AND ic.device_fingerprint_hash IS NULL;

CREATE INDEX idx_clients_device_fingerprint_hash
  ON clients(device_fingerprint_hash)
  WHERE device_fingerprint_hash IS NOT NULL;

CREATE INDEX idx_issued_configs_device_fingerprint_hash
  ON issued_configs(device_fingerprint_hash)
  WHERE device_fingerprint_hash IS NOT NULL;
