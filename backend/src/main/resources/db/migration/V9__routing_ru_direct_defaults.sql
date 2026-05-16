ALTER TABLE routing_default_rules
  DROP CONSTRAINT IF EXISTS routing_default_rules_match_type_check;

ALTER TABLE routing_default_rules
  ADD CONSTRAINT routing_default_rules_match_type_check
  CHECK (match_type IN ('DOMAIN', 'DOMAIN_SUFFIX', 'DOMAIN_KEYWORD', 'IP_CIDR', 'APP_PACKAGE', 'GEOIP'));

ALTER TABLE user_route_rules
  DROP CONSTRAINT IF EXISTS user_route_rules_match_type_check;

ALTER TABLE user_route_rules
  ADD CONSTRAINT user_route_rules_match_type_check
  CHECK (match_type IN ('DOMAIN', 'DOMAIN_SUFFIX', 'DOMAIN_KEYWORD', 'IP_CIDR', 'APP_PACKAGE', 'GEOIP'));

INSERT INTO routing_default_rules (
  rule_key, name, description, priority, enabled, match_type, match_values, action, editable, revision
) VALUES
  (
    'direct-ru-domain-keywords',
    'RU и основные сервисы напрямую',
    'Отправляет популярные RU/поисковые/платформенные домены напрямую.',
    250,
    TRUE,
    'DOMAIN_KEYWORD',
    ARRAY[
      'yandex',
      'yastatic',
      'yadi.sk',
      'xn--80aswg',
      'xn--d1acpjx3f.xn--p1ai',
      'xn--c1avg',
      'xn--80asehdb',
      'xn--p1acf',
      'xn--p1ai',
      'google.com',
      'gstatic.com',
      'yahoo',
      'bing',
      'tineye',
      'duckduckgo',
      'apple',
      'vk.com',
      'userapi.com',
      'vk-cdn.me',
      'mvk.com',
      'vk-cdn.net',
      'vk-portal.net',
      'vk.cc',
      'icq',
      'livejournal',
      'microsoft',
      'live.com',
      'login.live',
      'tradingview'
    ]::TEXT[],
    'DIRECT',
    TRUE,
    1
  ),
  (
    'direct-ru-domain-suffixes',
    'RU зоны напрямую',
    'Отправляет доменные зоны .ru, .su и .by напрямую.',
    260,
    TRUE,
    'DOMAIN_SUFFIX',
    ARRAY['ru', 'su', 'by']::TEXT[],
    'DIRECT',
    TRUE,
    1
  ),
  (
    'direct-mradx-domain',
    'MRADX напрямую',
    'Отправляет r.mradx.net напрямую.',
    270,
    TRUE,
    'DOMAIN',
    ARRAY['r.mradx.net']::TEXT[],
    'DIRECT',
    TRUE,
    1
  ),
  (
    'direct-ru-geoip-rule-sets',
    'RU GeoIP напрямую',
    'Отправляет geoip-ru и geoip-ru-blocked напрямую.',
    280,
    TRUE,
    'GEOIP',
    ARRAY['ru', 'ru-blocked']::TEXT[],
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
