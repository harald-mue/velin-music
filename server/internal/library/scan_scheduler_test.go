package library

import (
	"testing"
	"time"
)

func TestScanSchedulerStartStop(t *testing.T) {
	service, cleanup := openScanServiceTestDB(t)
	defer cleanup()

	scheduler := NewScanScheduler(service, 50*time.Millisecond)
	scheduler.Start()
	scheduler.Stop()
	scheduler.Stop()
}

func TestScanSchedulerDisabledForNonPositiveInterval(t *testing.T) {
	service, cleanup := openScanServiceTestDB(t)
	defer cleanup()

	scheduler := NewScanScheduler(service, 0)
	scheduler.Start()
	scheduler.Stop()
}
