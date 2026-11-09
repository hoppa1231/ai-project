-- Replace the former VPN-by-default policy; old DIRECT overrides are obsolete.
UPDATE policies SET default_route = 'DIRECT', version = version + 1,
 include_apps = '{}', exclude_apps = '{}', include_domains = '{}', exclude_domains = '{}';
DELETE FROM user_route_rules WHERE default_rule_key IS NOT NULL OR action <> 'VPN'
 OR match_type IN ('DOMAIN_KEYWORD', 'GEOIP')
 OR (match_type = 'IP_CIDR' AND EXISTS (SELECT 1 FROM unnest(match_values) AS v WHERE v ~ '/0$'))
 OR (match_type IN ('DOMAIN', 'DOMAIN_SUFFIX') AND EXISTS (
 SELECT 1 FROM unnest(match_values) AS v WHERE lower(v) ~ '(^|\.)(ru|su|xn--p1ai|рф)\.?$'));
UPDATE routing_default_rules SET enabled = FALSE WHERE action = 'DIRECT';
INSERT INTO routing_default_rules
(rule_key, name, description, priority, enabled, match_type, match_values, action, editable, revision) VALUES
('vpn-telegram-domains', 'Telegram', 'Домены Telegram', 400, TRUE, 'DOMAIN_SUFFIX',
 ARRAY['telegram.org','t.me','telegram.me','telesco.pe','telegra.ph'], 'VPN', FALSE, 1),
('vpn-telegram-ip', 'Telegram IP', 'Официальные сети core.telegram.org/resources/cidr.txt', 410, TRUE, 'IP_CIDR',
 ARRAY['91.108.56.0/22','91.108.4.0/22','91.108.8.0/22','91.108.16.0/22','91.108.12.0/22','149.154.160.0/20','91.105.192.0/23','91.108.20.0/22','185.76.151.0/24','2001:b28:f23d::/48','2001:b28:f23f::/48','2001:67c:4e8::/48','2001:b28:f23c::/48','2a0a:f280::/32'], 'VPN', FALSE, 1),
('vpn-services', 'Сервисы через VPN', 'Базовый список сервисов; доступность зависит от сети и региона', 500, TRUE, 'DOMAIN_SUFFIX',
 ARRAY['youtube.com','youtu.be','googlevideo.com','ytimg.com','ggpht.com','youtubei.googleapis.com','discord.com','discord.gg','discordapp.com','discordapp.net','discord.media','instagram.com','cdninstagram.com','facebook.com','facebook.net','fbcdn.net','fb.com','twitter.com','x.com','twimg.com','t.co','signal.org','signal.art','whispersystems.org','linkedin.com','licdn.com','medium.com','bbc.com','bbc.co.uk','dw.com','rferl.org','svoboda.org','meduza.io','theins.ru','tvrain.tv'], 'VPN', TRUE, 1);
