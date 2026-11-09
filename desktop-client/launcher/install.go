package main

import (
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
)

func copyDir(source, target string) error {
	return filepath.WalkDir(source, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("symlink")
		}
		relative, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}
		dest := filepath.Join(target, relative)
		if entry.IsDir() {
			return os.MkdirAll(dest, 0755)
		}
		in, err := os.Open(path)
		if err != nil {
			return err
		}
		defer in.Close()
		out, err := os.OpenFile(dest, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0644)
		if err != nil {
			return err
		}
		_, err = io.Copy(out, in)
		closeErr := out.Close()
		if err != nil {
			return err
		}
		return closeErr
	})
}

func replaceInstall(source, target string) error {

	if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
		return err
	}
	stage, err := os.MkdirTemp(filepath.Dir(target), "Chspch.new-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	if err = copyDir(source, stage); err != nil {
		return err
	}
	if err = os.WriteFile(filepath.Join(stage, "CHSPCH-INSTALL"), []byte("chspch-user-install-v1"), 0644); err != nil {
		return err
	}
	previous := target + ".previous"
	if _, err = os.Stat(previous); err == nil {
		marker, e := os.ReadFile(filepath.Join(previous, "CHSPCH-INSTALL"))
		if e != nil || string(marker) != "chspch-user-install-v1" {
			return fmt.Errorf("rollback path occupied")
		}
		if err = os.RemoveAll(previous); err != nil {
			return err
		}
	}
	hadPrevious := false
	if _, err = os.Stat(target); err == nil {
		if _, err = os.Stat(filepath.Join(target, "Chspch.exe")); err != nil {
			return fmt.Errorf("install path occupied")
		}
		if err = os.WriteFile(filepath.Join(target, "CHSPCH-INSTALL"), []byte("chspch-user-install-v1"), 0644); err != nil {
			return err
		}
		if err = os.Rename(target, previous); err != nil {
			return err
		}
		hadPrevious = true
	}
	if err = os.Rename(stage, target); err != nil {
		if hadPrevious {
			os.Rename(previous, target)
		}
		return err
	}

	return nil
}
