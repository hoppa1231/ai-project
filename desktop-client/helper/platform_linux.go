package main

import (
	"os"
	"os/exec"
	"syscall"
)

func privileged() bool           { return os.Geteuid() == 0 }
func coreName() string           { return "sing-box" }
func configureChild(c *exec.Cmd) { c.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGTERM} }
func stopChild(c *exec.Cmd) {
	if c.Process != nil {
		c.Process.Signal(syscall.SIGTERM)
	}
}

func afterStart(c *exec.Cmd) error { return nil }

func afterStop() {}
