package main

import (
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func fixture(t *testing.T) map[string]any {
	t.Helper()
	b, e := os.ReadFile("testdata/config.json")
	if e != nil {
		t.Fatal(e)
	}
	var c map[string]any
	if e = json.Unmarshal(b, &c); e != nil {
		t.Fatal(e)
	}
	return c
}
func TestGeneratedShapeAccepted(t *testing.T) {
	b, _ := os.ReadFile("testdata/config.json")
	if e := validateConfig(b); e != nil {
		t.Fatal(e)
	}
}
func TestDangerousFieldsRejected(t *testing.T) {
	tests := map[string]func(map[string]any){
		"log output":         func(c map[string]any) { obj(c["log"])["output"] = "/etc/overwrite" },
		"arbitrary listener": func(c map[string]any) { obj(arr(c["inbounds"])[0])["listen"] = "0.0.0.0" },
		"custom tun":         func(c map[string]any) { obj(arr(c["inbounds"])[1])["interface_name"] = "foreign0" },
		"all vpn default":    func(c map[string]any) { obj(c["route"])["final"] = "vpn" },
		"local rules": func(c map[string]any) {
			obj(c["route"])["rule_set"] = []any{map[string]any{"type": "local", "path": "/etc/secret"}}
		},
		"experimental output": func(c map[string]any) {
			c["experimental"] = map[string]any{"cache_file": map[string]any{"path": "/etc/overwrite"}}
		},
		"tls secret path": func(c map[string]any) {
			obj(arr(c["outbounds"])[0])["tls"] = map[string]any{"certificate_path": "/etc/secret"}
		},
		"outbound proxy": func(c map[string]any) { obj(arr(c["outbounds"])[0])["type"] = "socks" },
		"untrusted remote": func(c map[string]any) {
			obj(c["route"])["rule_set"] = []any{map[string]any{"type": "remote", "format": "binary", "url": "https://evil.invalid/rules"}}
		},
	}
	for name, mutate := range tests {
		t.Run(name, func(t *testing.T) {
			c := fixture(t)
			mutate(c)
			b, _ := json.Marshal(c)
			if validateConfig(b) == nil {
				t.Fatal("accepted unsafe config")
			}
		})
	}
}
func TestCoreCopyPinsTheBytes(t *testing.T) {
	dir := t.TempDir()
	source := filepath.Join(dir, "input")
	os.WriteFile(source, []byte("verified core"), 0600)
	old := coreHash
	defer func() { coreHash = old }()
	coreHash = fmt.Sprintf("%x", sha256.Sum256([]byte("verified core")))
	if e := copyCore(source, filepath.Join(dir, "good")); e != nil {
		t.Fatal(e)
	}
	os.WriteFile(source, []byte("replaced"), 0600)
	if copyCore(source, filepath.Join(dir, "bad")) == nil {
		t.Fatal("accepted swapped core")
	}
}
