INSERT INTO routing_default_rules (
  rule_key, name, description, priority, enabled, match_type, match_values, action, editable, revision
) VALUES (
  'direct-control-plane',
  'Панель управления напрямую',
  'Оставляет API приложения доступным даже при включенном VPN.',
  50,
  TRUE,
  'DOMAIN_SUFFIX',
  ARRAY['tech-supp-test.ru']::TEXT[],
  'DIRECT',
  TRUE,
  1
)
ON CONFLICT (rule_key) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  priority = EXCLUDED.priority,
  enabled = EXCLUDED.enabled,
  match_type = EXCLUDED.match_type,
  match_values = EXCLUDED.match_values,
  action = EXCLUDED.action,
  editable = EXCLUDED.editable,
  revision = routing_default_rules.revision + 1,
  updated_at = now();
