ALTER TABLE users
  ADD COLUMN telegram_id BIGINT UNIQUE,
  ADD COLUMN telegram_username TEXT,
  ADD COLUMN telegram_first_name TEXT,
  ADD COLUMN telegram_last_name TEXT,
  ADD COLUMN telegram_photo_url TEXT;

CREATE INDEX idx_users_telegram_id ON users(telegram_id) WHERE telegram_id IS NOT NULL;

ALTER TABLE node_client_inventory
  ADD COLUMN telegram_id BIGINT;

CREATE INDEX idx_node_client_inventory_telegram_id
  ON node_client_inventory(telegram_id)
  WHERE telegram_id IS NOT NULL;
