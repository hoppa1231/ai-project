package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestUpdateReplacesOldJarsAndKeepsSingleRollback(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "source")
	target := filepath.Join(root, "installed")
	os.MkdirAll(filepath.Join(source, "app"), 0755)
	os.WriteFile(filepath.Join(source, "Chspch.exe"), []byte("launcher"), 0644)
	os.WriteFile(filepath.Join(source, "app", "old.jar"), []byte("old"), 0644)
	if e := replaceInstall(source, target); e != nil {
		t.Fatal(e)
	}
	os.Remove(filepath.Join(source, "app", "old.jar"))
	os.WriteFile(filepath.Join(source, "app", "new.jar"), []byte("new"), 0644)
	for i := 0; i < 2; i++ {
		if e := replaceInstall(source, target); e != nil {
			t.Fatal(e)
		}
	}
	if _, e := os.Stat(filepath.Join(target, "app", "old.jar")); !os.IsNotExist(e) {
		t.Fatal("old JAR remained")
	}
	if _, e := os.Stat(filepath.Join(target, "app", "new.jar")); e != nil {
		t.Fatal(e)
	}
	if _, e := os.Stat(target + ".previous"); e != nil {
		t.Fatal("rollback missing")
	}
}
func TestForeignRollbackIsPreserved(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "source")
	target := filepath.Join(root, "installed")
	os.MkdirAll(source, 0755)
	os.WriteFile(filepath.Join(source, "Chspch.exe"), []byte("launcher"), 0644)
	os.MkdirAll(target+".previous", 0755)
	os.WriteFile(filepath.Join(target+".previous", "personal"), []byte("keep"), 0644)
	if e := replaceInstall(source, target); e == nil {
		t.Fatal("foreign rollback accepted")
	}
	if _, e := os.Stat(filepath.Join(target+".previous", "personal")); e != nil {
		t.Fatal(e)
	}
}
