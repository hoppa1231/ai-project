CREATE TABLE routing_default_rules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  rule_key TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  priority INTEGER NOT NULL CHECK (priority > 0),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  match_type TEXT NOT NULL CHECK (match_type IN ('DOMAIN_SUFFIX', 'DOMAIN_KEYWORD', 'IP_CIDR', 'APP_PACKAGE')),
  match_values TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
  action TEXT NOT NULL CHECK (action IN ('VPN', 'DIRECT', 'BLOCK')),
  editable BOOLEAN NOT NULL DEFAULT TRUE,
  revision INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_route_rules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  default_rule_key TEXT REFERENCES routing_default_rules(rule_key) ON DELETE CASCADE,
  name TEXT NOT NULL,
  priority INTEGER NOT NULL CHECK (priority > 0),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  match_type TEXT NOT NULL CHECK (match_type IN ('DOMAIN_SUFFIX', 'DOMAIN_KEYWORD', 'IP_CIDR', 'APP_PACKAGE')),
  match_values TEXT[] NOT NULL DEFAULT '{}'::TEXT[],
  action TEXT NOT NULL CHECK (action IN ('VPN', 'DIRECT', 'BLOCK')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_user_route_rule_default_override
  ON user_route_rules(user_id, default_rule_key)
  WHERE default_rule_key IS NOT NULL;

CREATE INDEX idx_user_route_rules_user_priority ON user_route_rules(user_id, priority);
CREATE INDEX idx_routing_default_rules_priority ON routing_default_rules(priority);

CREATE TRIGGER trg_routing_default_rules_updated_at
  BEFORE UPDATE ON routing_default_rules
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_user_route_rules_updated_at
  BEFORE UPDATE ON user_route_rules
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO routing_default_rules (
  rule_key, name, description, priority, enabled, match_type, match_values, action, editable, revision
) VALUES
  (
    'direct-private-networks',
    'Локальные сети напрямую',
    'Не отправляет частные и служебные сети в VPN-туннель.',
    100,
    TRUE,
    'IP_CIDR',
    ARRAY[
      '10.0.0.0/8',
      '100.64.0.0/10',
      '169.254.0.0/16',
      '172.16.0.0/12',
      '192.168.0.0/16',
      '224.0.0.0/4',
      '255.255.255.255/32',
      'fc00::/7',
      'fe80::/10',
      'ff00::/8'
    ]::TEXT[],
    'DIRECT',
    TRUE,
    1
  ),
  (
    'direct-connectivity-checks',
    'Проверки сети напрямую',
    'Оставляет системные проверки доступности сети вне VPN.',
    200,
    TRUE,
    'DOMAIN_SUFFIX',
    ARRAY[
      'connectivitycheck.gstatic.com',
      'clients3.google.com',
      'msftconnecttest.com',
      'msftncsi.com'
    ]::TEXT[],
    'DIRECT',
    TRUE,
    1
  )
ON CONFLICT (rule_key) DO NOTHING;
