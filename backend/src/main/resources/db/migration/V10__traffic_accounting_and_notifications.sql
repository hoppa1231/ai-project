CREATE TABLE app_notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  target_user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  target_device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  severity TEXT NOT NULL DEFAULT 'INFO' CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  active BOOLEAN NOT NULL DEFAULT true,
  starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ,
  created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT chk_app_notifications_scope CHECK (
    target_device_id IS NULL OR target_user_id IS NOT NULL
  ),
  CONSTRAINT chk_app_notifications_expiry CHECK (
    expires_at IS NULL OR expires_at > starts_at
  )
);

CREATE TABLE app_notification_reads (
  notification_id UUID NOT NULL REFERENCES app_notifications(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id UUID REFERENCES devices(id) ON DELETE CASCADE,
  read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (notification_id, user_id, device_id)
);

CREATE INDEX idx_app_notifications_active_scope
  ON app_notifications(active, starts_at DESC, target_user_id, target_device_id);

CREATE TRIGGER trg_app_notifications_updated_at
  BEFORE UPDATE ON app_notifications
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
