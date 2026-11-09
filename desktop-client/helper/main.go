// One elevated helper per GUI session, authenticated by private rendezvous.
// Idle between connections; heartbeat/EOF bounds its lifetime. No installed service.
package main

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

var coreHash string // pinned to the verified release by build-helper.py

type rendezvous struct {
	Port  int    `json:"port"`
	Token string `json:"token"`
}

func main() {
	if err := run(); err != nil {
		os.Exit(1)
	}
}
func run() error {
	rpath := flag.String("rendezvous", "", "private GUI rendezvous")
	source := flag.String("core", "", "verified bundled core")
	flag.Parse()
	if !privileged() {
		return errors.New("elevation required")
	}
	raw, err := os.ReadFile(*rpath)
	if err != nil || len(raw) > 4096 {
		return errors.New("invalid rendezvous")
	}
	var r rendezvous
	if json.Unmarshal(raw, &r) != nil || r.Port < 1024 || r.Port > 65535 || len(r.Token) != 64 {
		return errors.New("invalid rendezvous")
	}
	c, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", r.Port), 10*time.Second)
	if err != nil {
		return err
	}
	defer c.Close()
	fmt.Fprintln(c, r.Token)
	// Verified core is retained only for this authenticated GUI lifetime.
	dir, err := os.MkdirTemp("", "chspch-tun-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	os.Chmod(dir, 0700)
	core := filepath.Join(dir, coreName())
	if err = copyCore(*source, core); err != nil {
		fmt.Fprintln(c, "CORE_MISMATCH")
		return err
	}
	return serve(c, core, dir)
}

func serve(c net.Conn, core, dir string) error {
	var writes sync.Mutex
	send := func(s string) {
		writes.Lock()
		defer writes.Unlock()
		c.SetWriteDeadline(time.Now().Add(3 * time.Second))
		fmt.Fprintln(c, s)
	}
	var cmd *exec.Cmd
	var done chan error
	stop := func() {
		if cmd != nil {
			stopChild(cmd)
			select {
			case <-done:
			case <-time.After(8 * time.Second):
				cmd.Process.Kill()
				<-done
			}
			afterStop()
			cmd = nil
		}
		os.Remove(filepath.Join(dir, "config.json"))
	}
	defer stop()
	scanner := bufio.NewScanner(c)
	scanner.Buffer(make([]byte, 4096), (2<<20)+4096)
	for {
		c.SetReadDeadline(time.Now().Add(12 * time.Second))
		if !scanner.Scan() {
			return scanner.Err()
		}
		line := scanner.Bytes()
		switch strings.TrimSpace(string(line)) {
		case "ping":
			if cmd != nil {
				select {
				case <-done:
					afterStop()
					cmd = nil
					os.Remove(filepath.Join(dir, "config.json"))
					send("EXITED")
				default:
				}
			}
			send("ALIVE")
		case "stop":
			stop()
			send("STOPPED")
		case "quit":
			stop()
			send("STOPPED")
			return nil
		default:
			if len(line) > 2<<20 || validateConfig(line) != nil {
				send("INVALID_CONFIG")
				continue
			}
			stop()
			cfg := filepath.Join(dir, "config.json")
			if err := os.WriteFile(cfg, line, 0600); err != nil {
				return err
			}
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			check := exec.CommandContext(ctx, core, "check", "-c", cfg)
			check.Dir = dir
			err := check.Run()
			cancel()
			if err != nil {
				os.Remove(cfg)
				send("INVALID_CONFIG")
				continue
			}
			child := exec.Command(core, "run", "-c", cfg)
			child.Dir = dir
			configureChild(child)
			pipe, err := child.StdoutPipe()
			if err != nil {
				return err
			}
			child.Stderr = child.Stdout
			if err = child.Start(); err != nil {
				send("START_FAILED")
				continue
			}
			if err = afterStart(child); err != nil {
				child.Process.Kill()
				child.Wait()
				return err
			}
			cmd = child
			done = make(chan error, 1)
			go func(child *exec.Cmd, done chan error) {
				logs := bufio.NewScanner(pipe)
				logs.Buffer(make([]byte, 4096), (2<<20)+4096)
				for logs.Scan() {
					s := logs.Text()
					if strings.Contains(s, "outbound/vless[") && strings.Contains(s, "outbound connection") {
						send("ROUTED_VPN")
					}
					if strings.Contains(s, "outbound/direct[") && strings.Contains(s, "outbound connection") {
						send("ROUTED_DIRECT")
					}
					if strings.Contains(s, "sing-box started") {
						send("READY")
					}
					if strings.Contains(s, "permission") || strings.Contains(s, "Access is denied") {
						send("PERMISSION_DENIED")
					}
				}
				done <- child.Wait()
			}(child, done)
		}
	}
}

func copyCore(source, destination string) error {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0700)
	if err != nil {
		return err
	}
	h := sha256.New()
	_, err = io.Copy(io.MultiWriter(out, h), io.LimitReader(in, 160<<20))
	closeErr := out.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	expected, e := hex.DecodeString(coreHash)
	if e != nil || len(expected) != 32 || subtle.ConstantTimeCompare(h.Sum(nil), expected) != 1 {
		return errors.New("core hash mismatch")
	}
	return nil
}
