ALTER TABLE routing_default_rules
  DROP CONSTRAINT IF EXISTS routing_default_rules_match_type_check;

ALTER TABLE routing_default_rules
  ADD CONSTRAINT routing_default_rules_match_type_check
  CHECK (match_type IN ('DOMAIN_SUFFIX', 'DOMAIN_KEYWORD', 'IP_CIDR', 'APP_PACKAGE', 'GEOIP'));

ALTER TABLE user_route_rules
  DROP CONSTRAINT IF EXISTS user_route_rules_match_type_check;

ALTER TABLE user_route_rules
  ADD CONSTRAINT user_route_rules_match_type_check
  CHECK (match_type IN ('DOMAIN_SUFFIX', 'DOMAIN_KEYWORD', 'IP_CIDR', 'APP_PACKAGE', 'GEOIP'));
