CREATE TYPE account_type AS ENUM ('GUEST', 'REGISTERED');

ALTER TABLE users
  ADD COLUMN account_type account_type NOT NULL DEFAULT 'REGISTERED',
  ADD COLUMN registered_at TIMESTAMPTZ;

UPDATE users
SET registered_at = COALESCE(registered_at, created_at)
WHERE account_type = 'REGISTERED';

CREATE TABLE quota_wallets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  cycle_start DATE NOT NULL,
  free_bytes BIGINT NOT NULL CHECK (free_bytes >= 0),
  purchased_bytes BIGINT NOT NULL DEFAULT 0 CHECK (purchased_bytes >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_quota_wallet_user_cycle UNIQUE (user_id, cycle_start)
);

CREATE TABLE quota_topups (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  cycle_start DATE NOT NULL,
  bytes BIGINT NOT NULL CHECK (bytes > 0),
  source TEXT NOT NULL,
  external_ref TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_quota_topups_external_ref UNIQUE (external_ref)
);

CREATE INDEX idx_quota_wallets_user_cycle ON quota_wallets(user_id, cycle_start DESC);
CREATE INDEX idx_quota_topups_user_cycle ON quota_topups(user_id, cycle_start DESC);

CREATE TRIGGER trg_quota_wallets_updated_at
  BEFORE UPDATE ON quota_wallets
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
