package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
)

// Unknown keys are rejected at EVERY level. In particular: no local rule-set
// paths, log files, cache files, custom interfaces, process execution or listeners.
func keys(m map[string]any, allowed string) error {
	set := map[string]bool{}
	for _, k := range strings.Fields(allowed) {
		set[k] = true
	}
	for k := range m {
		if !set[k] {
			return fmt.Errorf("unsupported field %s", k)
		}
	}
	return nil
}
func obj(v any) map[string]any { m, _ := v.(map[string]any); return m }
func arr(v any) []any          { a, _ := v.([]any); return a }
func str(v any) string         { s, _ := v.(string); return s }
func validateConfig(raw []byte) error {
	var c map[string]any
	if json.Unmarshal(raw, &c) != nil {
		return errors.New("json")
	}
	if e := keys(c, "log inbounds outbounds route dns"); e != nil {
		return e
	}
	if e := keys(obj(c["log"]), "level"); e != nil {
		return e
	}
	if str(obj(c["log"])["level"]) != "info" {
		return errors.New("log")
	}
	tun := false
	for _, v := range arr(c["inbounds"]) {
		m := obj(v)
		switch str(m["type"]) {
		case "mixed":
			if e := keys(m, "type tag listen listen_port"); e != nil {
				return e
			}
			if m["listen"] != "127.0.0.1" || m["listen_port"] != float64(17890) {
				return errors.New("listen")
			}
		case "tun":
			if e := keys(m, "type tag interface_name address mtu auto_route strict_route route_exclude_address auto_redirect dns_mode"); e != nil {
				return e
			}
			if m["interface_name"] != "chspch0" || m["auto_route"] != true || m["strict_route"] != true || m["dns_mode"] != "hijack" {
				return errors.New("tun")
			}
			addresses := arr(m["address"])
			if len(addresses) != 2 || addresses[0] != "172.29.255.1/30" || addresses[1] != "fdfe:dcba:9876::1/126" {
				return errors.New("address")
			}
			tun = true
		default:
			return errors.New("inbound")
		}
	}
	if !tun || len(arr(c["inbounds"])) != 2 {
		return errors.New("tun required")
	}
	for _, v := range arr(c["outbounds"]) {
		m := obj(v)
		if e := keys(m, "type tag server server_port uuid flow network tls domain_resolver detour packet_encoding"); e != nil {
			return e
		}
		if m["type"] != "vless" && m["type"] != "direct" {
			return errors.New("outbound")
		}
		if tls := obj(m["tls"]); tls != nil {
			if e := keys(tls, "enabled server_name utls reality alpn"); e != nil {
				return e
			}
			if e := keys(obj(tls["utls"]), "enabled fingerprint"); e != nil {
				return e
			}
			if e := keys(obj(tls["reality"]), "enabled public_key short_id"); e != nil {
				return e
			}
		}
	}
	route := obj(c["route"])
	if route["final"] != "direct" {
		return errors.New("default must be direct")
	}
	if e := keys(route, "auto_detect_interface default_domain_resolver rules rule_set final"); e != nil {
		return e
	}
	for _, v := range arr(route["rules"]) {
		m := obj(v)
		if e := keys(m, "action outbound protocol ip_is_private domain domain_suffix domain_keyword ip_cidr rule_set process_path"); e != nil {
			return e
		}
		if a := str(m["action"]); a != "" && a != "sniff" && a != "hijack-dns" && a != "reject" {
			return errors.New("action")
		}
	}
	for _, v := range arr(route["rule_set"]) {
		m := obj(v)
		if e := keys(m, "type tag format url download_detour"); e != nil {
			return e
		}
		u, e := url.Parse(str(m["url"]))
		if e != nil || u.Scheme != "https" || u.Host != "raw.githubusercontent.com" || m["type"] != "remote" || m["format"] != "binary" {
			return errors.New("rule set")
		}
		if !strings.HasPrefix(u.Path, "/SagerNet/sing-geoip/rule-set/") && !strings.HasPrefix(u.Path, "/runetfreedom/russia-v2ray-rules-dat/release/sing-box/rule-set-geoip/") {
			return errors.New("rule source")
		}
	}
	dns := obj(c["dns"])
	if e := keys(dns, "servers rules final reverse_mapping"); e != nil {
		return e
	}
	for _, v := range arr(dns["servers"]) {
		m := obj(v)
		if e := keys(m, "type tag server detour"); e != nil {
			return e
		}
		if m["server"] != "1.1.1.1" || (m["type"] != "udp" && m["type"] != "tcp") {
			return errors.New("dns server")
		}
	}
	for _, v := range arr(dns["rules"]) {
		if e := keys(obj(v), "domain domain_suffix domain_keyword server"); e != nil {
			return e
		}
	}
	return nil
}
