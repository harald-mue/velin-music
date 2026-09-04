package library

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"testing"

	"github.com/harald-mue/velin-music/server/internal/db"
)

func TestValidateRootCanonicalizesAbsoluteDirectory(t *testing.T) {
	parent := t.TempDir()
	rootPath := filepath.Join(parent, "music")
	if err := os.Mkdir(rootPath, 0o755); err != nil {
		t.Fatalf("create root: %v", err)
	}

	root, err := ValidateRoot(filepath.Join(rootPath, "."))
	if err != nil {
		t.Fatalf("ValidateRoot() error = %v", err)
	}
	if !filepath.IsAbs(root.Path) {
		t.Fatalf("root path = %q, want absolute path", root.Path)
	}
	if filepath.Clean(root.Path) != rootPath {
		t.Fatalf("root path = %q, want %q", root.Path, rootPath)
	}
	if root.ID != "" {
		t.Fatalf("validated root ID = %q, want empty before persistence", root.ID)
	}
}

func TestValidateRootRejectsFilesAndSymlinks(t *testing.T) {
	parent := t.TempDir()
	filePath := filepath.Join(parent, "music.flac")
	if err := os.WriteFile(filePath, []byte("audio"), 0o600); err != nil {
		t.Fatalf("create file: %v", err)
	}
	if _, err := ValidateRoot(filePath); err == nil {
		t.Fatal("ValidateRoot(file) error = nil, want error")
	}

	target := filepath.Join(parent, "target")
	if err := os.Mkdir(target, 0o755); err != nil {
		t.Fatalf("create target: %v", err)
	}
	link := filepath.Join(parent, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if _, err := ValidateRoot(link); err == nil {
		t.Fatal("ValidateRoot(symlink) error = nil, want error")
	}
}

func TestDiscoverFindsFLACAndMP3WithoutFollowingSymlinks(t *testing.T) {
	parent := t.TempDir()
	rootPath := filepath.Join(parent, "music")
	if err := os.Mkdir(rootPath, 0o755); err != nil {
		t.Fatalf("create root: %v", err)
	}
	if err := os.Mkdir(filepath.Join(rootPath, "album"), 0o755); err != nil {
		t.Fatalf("create album: %v", err)
	}

	files := map[string][]byte{
		"album/01-song.FLAC": []byte("flac data"),
		"album/02-song.mp3":  []byte("mp3 data"),
		"album/cover.jpg":    []byte("not indexed"),
	}
	checksums := make(map[string][32]byte)
	for relative, contents := range files {
		path := filepath.Join(rootPath, filepath.FromSlash(relative))
		if err := os.WriteFile(path, contents, 0o600); err != nil {
			t.Fatalf("write %s: %v", relative, err)
		}
		checksums[relative] = sha256.Sum256(contents)
	}

	outside := filepath.Join(parent, "outside.mp3")
	if err := os.WriteFile(outside, []byte("outside"), 0o600); err != nil {
		t.Fatalf("write outside file: %v", err)
	}
	linkedFile := filepath.Join(rootPath, "linked.mp3")
	if err := os.Symlink(outside, linkedFile); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	outsideDir := filepath.Join(parent, "outside-dir")
	if err := os.Mkdir(outsideDir, 0o755); err != nil {
		t.Fatalf("create outside directory: %v", err)
	}
	if err := os.WriteFile(filepath.Join(outsideDir, "escaped.flac"), []byte("outside"), 0o600); err != nil {
		t.Fatalf("write escaped file: %v", err)
	}
	if err := os.Symlink(outsideDir, filepath.Join(rootPath, "linked-dir")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}

	root, err := ValidateRoot(rootPath)
	if err != nil {
		t.Fatalf("ValidateRoot() error = %v", err)
	}
	var discovered []MediaFile
	if err := Discover(context.Background(), root, func(file MediaFile) error {
		discovered = append(discovered, file)
		return nil
	}); err != nil {
		t.Fatalf("Discover() error = %v", err)
	}

	got := make([]string, 0, len(discovered))
	formats := make(map[string]Format)
	for _, file := range discovered {
		got = append(got, file.RelativePath)
		formats[file.RelativePath] = file.Format
	}
	want := []string{"album/01-song.FLAC", "album/02-song.mp3"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("discovered paths = %#v, want %#v", got, want)
	}
	if formats[want[0]] != FormatFLAC || formats[want[1]] != FormatMP3 {
		t.Fatalf("discovered formats = %#v", formats)
	}
	for relative, wantChecksum := range checksums {
		contents, err := os.ReadFile(filepath.Join(rootPath, filepath.FromSlash(relative)))
		if err != nil {
			t.Fatalf("read %s after discovery: %v", relative, err)
		}
		if gotChecksum := sha256.Sum256(contents); gotChecksum != wantChecksum {
			t.Fatalf("source file %s changed during discovery", relative)
		}
	}
}

func TestDiscoverHonorsCancellationAndCallbackErrors(t *testing.T) {
	rootPath := filepath.Join(t.TempDir(), "music")
	if err := os.Mkdir(rootPath, 0o755); err != nil {
		t.Fatalf("create root: %v", err)
	}
	if err := os.WriteFile(filepath.Join(rootPath, "song.mp3"), []byte("audio"), 0o600); err != nil {
		t.Fatalf("write audio: %v", err)
	}
	root, err := ValidateRoot(rootPath)
	if err != nil {
		t.Fatalf("ValidateRoot() error = %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := Discover(ctx, root, func(MediaFile) error { return nil }); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled Discover() error = %v, want context.Canceled", err)
	}

	wantErr := errors.New("stop indexing")
	err = Discover(context.Background(), root, func(MediaFile) error { return wantErr })
	if !errors.Is(err, wantErr) {
		t.Fatalf("callback Discover() error = %v, want %v", err, wantErr)
	}
}

func TestDiscoverRejectsPathChange(t *testing.T) {
	parent := t.TempDir()
	rootPath := filepath.Join(parent, "music")
	if err := os.Mkdir(rootPath, 0o755); err != nil {
		t.Fatalf("create root: %v", err)
	}
	root, err := ValidateRoot(rootPath)
	if err != nil {
		t.Fatalf("ValidateRoot() error = %v", err)
	}
	if err := os.Rename(rootPath, filepath.Join(parent, "renamed")); err != nil {
		t.Fatalf("rename root: %v", err)
	}
	if err := Discover(context.Background(), root, func(MediaFile) error { return nil }); err == nil {
		t.Fatal("Discover() error = nil, want changed-root error")
	}
}

func TestStoreRoots(t *testing.T) {
	parent := t.TempDir()
	firstPath := filepath.Join(parent, "first")
	secondPath := filepath.Join(parent, "second")
	for _, path := range []string{firstPath, secondPath} {
		if err := os.Mkdir(path, 0o755); err != nil {
			t.Fatalf("create root: %v", err)
		}
	}

	database := openLibraryTestDB(t)
	store := NewStore(database)
	first, err := store.AddRoot(context.Background(), filepath.Join(firstPath, "."))
	if err != nil {
		t.Fatalf("add first root: %v", err)
	}
	second, err := store.AddRoot(context.Background(), secondPath)
	if err != nil {
		t.Fatalf("add second root: %v", err)
	}
	if first.ID == "" || second.ID == "" || first.ID == second.ID {
		t.Fatalf("root IDs = %q and %q", first.ID, second.ID)
	}

	roots, err := store.ListRoots(context.Background())
	if err != nil {
		t.Fatalf("list roots: %v", err)
	}
	sort.Slice(roots, func(i, j int) bool { return roots[i].Path < roots[j].Path })
	wantPaths := []string{first.Path, second.Path}
	gotPaths := []string{roots[0].Path, roots[1].Path}
	sort.Strings(wantPaths)
	sort.Strings(gotPaths)
	if !reflect.DeepEqual(gotPaths, wantPaths) {
		t.Fatalf("root paths = %#v, want %#v", gotPaths, wantPaths)
	}

	if _, err := store.AddRoot(context.Background(), firstPath); err == nil {
		t.Fatal("duplicate AddRoot() error = nil, want error")
	}
	if err := store.RemoveRoot(context.Background(), first.ID); err != nil {
		t.Fatalf("remove root: %v", err)
	}
	if err := store.RemoveRoot(context.Background(), first.ID); err == nil {
		t.Fatal("second RemoveRoot() error = nil, want not-found error")
	}
}

func openLibraryTestDB(t *testing.T) *sql.DB {
	t.Helper()
	// Keep root-store tests on the production schema and SQLite configuration.
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open library test database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })
	return database
}
