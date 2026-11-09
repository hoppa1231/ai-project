package main

import (
	"os/exec"
	"syscall"
	"unsafe"
)

var shell32 = syscall.NewLazyDLL("shell32.dll")
var kernel32 = syscall.NewLazyDLL("kernel32.dll")

func privileged() bool { v, _, _ := shell32.NewProc("IsUserAnAdmin").Call(); return v != 0 }
func coreName() string { return "sing-box.exe" }
func configureChild(c *exec.Cmd) {
	c.SysProcAttr = &syscall.SysProcAttr{CreationFlags: 0x00000200 | 0x00000010, HideWindow: true}
}
func stopChild(c *exec.Cmd) {
	if c.Process == nil {
		return
	}
	kernel32.NewProc("FreeConsole").Call()
	ok, _, _ := kernel32.NewProc("AttachConsole").Call(uintptr(c.Process.Pid))
	if ok != 0 {
		kernel32.NewProc("SetConsoleCtrlHandler").Call(0, 1)
		kernel32.NewProc("GenerateConsoleCtrlEvent").Call(1, uintptr(c.Process.Pid))
		kernel32.NewProc("FreeConsole").Call()
	}
}

// A job object makes the kernel close the core if the elevated helper is killed.
// This supplements graceful CTRL_BREAK cleanup and the GUI heartbeat lease.
type basicLimits struct {
	PerProcessUserTime    int64
	PerJobUserTime        int64
	LimitFlags            uint32
	MinimumWorkingSetSize uintptr
	MaximumWorkingSetSize uintptr
	ActiveProcessLimit    uint32
	Affinity              uintptr
	PriorityClass         uint32
	SchedulingClass       uint32
}
type ioCounters struct{ ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount uint64 }
type extendedLimits struct {
	BasicLimitInformation                                                        basicLimits
	IoInfo                                                                       ioCounters
	ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed uintptr
}

var jobHandle syscall.Handle

func afterStart(c *exec.Cmd) error {
	job, _, err := kernel32.NewProc("CreateJobObjectW").Call(0, 0)
	if job == 0 {
		return err
	}
	limits := extendedLimits{}
	limits.BasicLimitInformation.LimitFlags = 0x2000
	ok, _, err := kernel32.NewProc("SetInformationJobObject").Call(job, 9, uintptr(unsafe.Pointer(&limits)), unsafe.Sizeof(limits))
	if ok == 0 {
		syscall.CloseHandle(syscall.Handle(job))
		return err
	}
	process, err := syscall.OpenProcess(0x0100|0x0001, false, uint32(c.Process.Pid))
	if err != nil {
		syscall.CloseHandle(syscall.Handle(job))
		return err
	}
	defer syscall.CloseHandle(process)
	ok, _, err = kernel32.NewProc("AssignProcessToJobObject").Call(job, uintptr(process))
	if ok == 0 {
		syscall.CloseHandle(syscall.Handle(job))
		return err
	}
	jobHandle = syscall.Handle(job)
	return nil
}

func afterStop() {
	if jobHandle != 0 {
		syscall.CloseHandle(jobHandle)
		jobHandle = 0
	}
}
