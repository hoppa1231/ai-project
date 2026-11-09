package main

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSessionReusesAuthorizationAndAcceptsOnlyValidatedConfigurations(t *testing.T) {
	dir := t.TempDir()
	core := filepath.Join(dir, "core")
	if err := os.WriteFile(core, []byte("#!/bin/sh\nif [ \"$1\" = check ]; then exit 0; fi\necho 'sing-box started'\nexec sleep 60\n"), 0700); err != nil {
		t.Fatal(err)
	}
	config, err := os.ReadFile("testdata/config.json")
	if err != nil {
		t.Fatal(err)
	}
	server, client := net.Pipe()
	defer client.Close()
	done := make(chan error, 1)
	go func() { defer server.Close(); done <- serve(server, core, dir) }()
	reader := bufio.NewReader(client)
	exchange := func(command, want string) {
		t.Helper()
		client.SetDeadline(time.Now().Add(12 * time.Second))
		fmt.Fprintln(client, command)
		line, e := reader.ReadString('\n')
		if e != nil || line != want+"\n" {
			t.Fatalf("want %s; got %q err %v", want, line, e)
		}
	}
	exchange("{}", "INVALID_CONFIG")
	for i := 0; i < 2; i++ {
		exchange(string(config), "READY")
		exchange("stop", "STOPPED")
		if _, err := os.Stat(filepath.Join(dir, "config.json")); !os.IsNotExist(err) {
			t.Fatal("config remains after stop")
		}
		exchange("ping", "ALIVE")
	}
	exchange("quit", "STOPPED")
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("helper did not exit")
	}
}

func TestSessionEOFStopsActiveCore(t *testing.T) {
	dir := t.TempDir()
	core := filepath.Join(dir, "core")
	os.WriteFile(core, []byte("#!/bin/sh\nif [ \"$1\" = check ]; then exit 0; fi\necho 'sing-box started'\nexec sleep 60\n"), 0700)
	config, _ := os.ReadFile("testdata/config.json")
	server, client := net.Pipe()
	done := make(chan error, 1)
	go func() { defer server.Close(); done <- serve(server, core, dir) }()
	client.SetDeadline(time.Now().Add(12 * time.Second))
	fmt.Fprintln(client, string(config))
	ready, err := bufio.NewReader(client).ReadString('\n')
	if err != nil || ready != "READY\n" {
		t.Fatalf("core not ready %q %v", ready, err)
	}
	client.Close()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("parent EOF left helper running")
	}
	if _, err := os.Stat(filepath.Join(dir, "config.json")); !os.IsNotExist(err) {
		t.Fatal("config survived parent EOF")
	}
}
