// A normal, unelevated Windows launcher. No PowerShell/VBScript is needed to run.
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

func message(text string) {
	t, _ := syscall.UTF16PtrFromString(text)
	caption, _ := syscall.UTF16PtrFromString("ЧСПЧ")
	syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(caption)), 0x40)
}
func quote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
func powershell(script string) error {
	c := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return c.Run()
}
func install(source string) (string, error) {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		return "", fmt.Errorf("no local app data")
	}
	target := filepath.Join(base, "Programs", "Chspch")
	lock := filepath.Join(base, "chspch", "desktop.lock")
	if _, err := os.Stat(lock); err == nil {
		script := "try { $f=[System.IO.File]::Open(" + quote(lock) + ",[System.IO.FileMode]::Open,[System.IO.FileAccess]::ReadWrite,[System.IO.FileShare]::None); $f.Close(); exit 0 } catch { exit 1 }"
		if powershell(script) != nil {
			return "", fmt.Errorf("running")
		}
	}
	if !strings.EqualFold(source, target) {
		if err := replaceInstall(source, target); err != nil {
			return "", err
		}
	}

	exe := filepath.Join(target, "Chspch.exe")
	script := "$w=New-Object -ComObject WScript.Shell; foreach($dir in @($w.SpecialFolders.Item('Desktop'),$w.SpecialFolders.Item('Programs'))) { $s=$w.CreateShortcut((Join-Path $dir 'ЧСПЧ.lnk')); $s.TargetPath=" + quote(exe) + "; $s.WorkingDirectory=" + quote(target) + "; $s.Save() }"
	if powershell(script) != nil {
		message("Приложение установлено. Ярлык создать не удалось; откройте Chspch.exe в папке установки.")
	}
	return target, nil
}
func main() {
	exe, err := os.Executable()
	if err != nil {
		return
	}
	root := filepath.Dir(exe)
	if strings.HasPrefix(strings.ToLower(filepath.Base(exe)), "install") || len(os.Args) > 1 && os.Args[1] == "--install" {
		root, err = install(root)
		if err != nil {
			message("Не удалось установить ЧСПЧ. Закройте запущенное приложение и повторите установку.")
			return
		}
	}
	java := filepath.Join(root, "runtime", "bin", "javaw.exe")
	if _, err = os.Stat(java); err != nil {
		message("Не найден встроенный Java runtime. Распакуйте всю папку из архива.")
		return
	}
	command := exec.Command(java, "-Dcompose.application.resources.dir="+filepath.Join(root, "resources"), "-Dchspch.app.dir="+root, "-cp", filepath.Join(root, "app", "*"), "app.chspch.desktop.MainKt")
	command.Dir = root
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if command.Start() != nil {
		message("Не удалось запустить ЧСПЧ. Проверьте полноту распакованного архива.")
	}
}
