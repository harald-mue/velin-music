// Package library contains safe library-root and media discovery logic.
package library

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Root is a validated, canonical library root. Path is internal and must never
// be returned through the public API.
type Root struct {
	ID        string
	Path      string
	CreatedAt time.Time
}

// ValidateRoot verifies that path names an existing, non-symlink directory and
// returns its canonical absolute path.
func ValidateRoot(path string) (Root, error) {
	if strings.TrimSpace(path) == "" {
		return Root{}, errors.New("library root path must not be empty")
	}

	absolute, err := filepath.Abs(path)
	if err != nil {
		return Root{}, fmt.Errorf("resolve library root: %w", err)
	}
	absolute = filepath.Clean(absolute)

	info, err := os.Lstat(absolute)
	if err != nil {
		return Root{}, fmt.Errorf("inspect library root: %w", err)
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return Root{}, errors.New("library root must not be a symlink")
	}
	if !info.IsDir() {
		return Root{}, fmt.Errorf("library root %q is not a directory", absolute)
	}

	canonical, err := filepath.EvalSymlinks(absolute)
	if err != nil {
		return Root{}, fmt.Errorf("canonicalize library root: %w", err)
	}
	canonical, err = filepath.Abs(canonical)
	if err != nil {
		return Root{}, fmt.Errorf("resolve canonical library root: %w", err)
	}
	canonical = filepath.Clean(canonical)

	canonicalInfo, err := os.Stat(canonical)
	if err != nil {
		return Root{}, fmt.Errorf("inspect canonical library root: %w", err)
	}
	if !canonicalInfo.IsDir() {
		return Root{}, fmt.Errorf("canonical library root %q is not a directory", canonical)
	}
	return Root{Path: canonical}, nil
}

// Store persists validated library roots in SQLite.
type Store struct {
	db *sql.DB
}

// NewStore creates a library-root store backed by db.
func NewStore(db *sql.DB) *Store {
	return &Store{db: db}
}

// AddRoot validates path before persisting it. The source filesystem is never modified.
func (s *Store) AddRoot(ctx context.Context, path string) (Root, error) {
	if s == nil || s.db == nil {
		return Root{}, errors.New("library store has no database")
	}

	root, err := ValidateRoot(path)
	if err != nil {
		return Root{}, err
	}
	root.ID, err = newID()
	if err != nil {
		return Root{}, fmt.Errorf("generate library root ID: %w", err)
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	_, err = s.db.ExecContext(ctx, `
		INSERT INTO library_roots (id, path, created_at, updated_at)
		VALUES (?, ?, ?, ?)`, root.ID, root.Path, now, now)
	if err != nil {
		if strings.Contains(err.Error(), "UNIQUE") {
			return Root{}, ErrRootExists
		}
		return Root{}, fmt.Errorf("store library root: %w", err)
	}
	root.CreatedAt, _ = time.Parse(time.RFC3339Nano, now)
	return root, nil
}

// ListRoots returns roots in stable path order. Paths are intentionally kept
// available only to internal server code.
func (s *Store) ListRoots(ctx context.Context) ([]Root, error) {
	if s == nil || s.db == nil {
		return nil, errors.New("library store has no database")
	}

	rows, err := s.db.QueryContext(ctx, "SELECT id, path, created_at FROM library_roots ORDER BY path")
	if err != nil {
		return nil, fmt.Errorf("list library roots: %w", err)
	}
	defer rows.Close()

	var roots []Root
	for rows.Next() {
		var root Root
		var createdAt string
		if err := rows.Scan(&root.ID, &root.Path, &createdAt); err != nil {
			return nil, fmt.Errorf("scan library root: %w", err)
		}
		root.CreatedAt, _ = time.Parse(time.RFC3339Nano, createdAt)
		roots = append(roots, root)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate library roots: %w", err)
	}
	return roots, nil
}

// GetRoot returns one configured library root by ID.
func (s *Store) GetRoot(ctx context.Context, id string) (Root, error) {
	if s == nil || s.db == nil {
		return Root{}, errors.New("library store has no database")
	}
	if strings.TrimSpace(id) == "" {
		return Root{}, errors.New("library root ID must not be empty")
	}

	var root Root
	var createdAt string
	err := s.db.QueryRowContext(ctx, "SELECT id, path, created_at FROM library_roots WHERE id = ?", id).Scan(&root.ID, &root.Path, &createdAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Root{}, ErrRootNotFound
	}
	if err != nil {
		return Root{}, fmt.Errorf("get library root: %w", err)
	}
	root.CreatedAt, _ = time.Parse(time.RFC3339Nano, createdAt)
	return root, nil
}

// RemoveRoot deletes a root and its indexed tracks. It does not touch the
// source directory or any source files.
func (s *Store) RemoveRoot(ctx context.Context, id string) error {
	if s == nil || s.db == nil {
		return errors.New("library store has no database")
	}
	if strings.TrimSpace(id) == "" {
		return errors.New("library root ID must not be empty")
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin remove library root: %w", err)
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(ctx, `
		DELETE FROM library_fts
		WHERE entity_type = 'track'
		  AND entity_id IN (SELECT id FROM tracks WHERE root_id = ?)`, id); err != nil {
		return fmt.Errorf("remove root search entries: %w", err)
	}
	result, err := tx.ExecContext(ctx, "DELETE FROM library_roots WHERE id = ?", id)
	if err != nil {
		return fmt.Errorf("remove library root: %w", err)
	}
	removed, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("check removed library root: %w", err)
	}
	if removed != 1 {
		return ErrRootNotFound
	}
	if err := cleanupLibraryIndex(ctx, tx); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit removed library root: %w", err)
	}
	return nil
}

func newID() (string, error) {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw[:]), nil
}
