package library

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Format identifies a supported source audio format.
type Format string

const (
	FormatFLAC Format = "flac"
	FormatMP3  Format = "mp3"
)

// MediaFile is the bounded filesystem metadata needed by the indexer. The
// path is relative to the validated root and never intended for API output.
type MediaFile struct {
	RelativePath string
	Format       Format
	Size         int64
	ModifiedAt   time.Time
}

// Discover visits supported FLAC and MP3 files below root without collecting
// the complete library in memory. The callback is called in deterministic
// lexical path order by filepath.WalkDir.
func Discover(ctx context.Context, root Root, visit func(MediaFile) error) error {
	if visit == nil {
		return errors.New("media discovery callback must not be nil")
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	validated, err := ValidateRoot(root.Path)
	if err != nil {
		return fmt.Errorf("validate discovery root: %w", err)
	}
	if validated.Path != root.Path {
		return errors.New("library root canonical path changed")
	}

	err = filepath.WalkDir(root.Path, func(path string, entry os.DirEntry, walkErr error) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if walkErr != nil {
			return fmt.Errorf("walk %q: %w", path, walkErr)
		}
		if entry == nil {
			return errors.New("walk returned a nil directory entry")
		}

		// WalkDir does not follow directory symlinks, but explicitly skipping
		// every symlink also prevents a linked media file from entering the index.
		if entry.Type()&os.ModeSymlink != 0 {
			if entry.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if entry.IsDir() {
			return nil
		}
		if !entry.Type().IsRegular() {
			return nil
		}

		format, ok := supportedFormat(path)
		if !ok {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return fmt.Errorf("inspect media file: %w", err)
		}
		if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
			return nil
		}

		relativePath, err := filepath.Rel(root.Path, path)
		if err != nil {
			return fmt.Errorf("make media path relative: %w", err)
		}
		return visit(MediaFile{
			RelativePath: filepath.ToSlash(relativePath),
			Format:       format,
			Size:         info.Size(),
			ModifiedAt:   info.ModTime(),
		})
	})
	if err != nil {
		return fmt.Errorf("discover media: %w", err)
	}
	return nil
}

func supportedFormat(path string) (Format, bool) {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".flac":
		return FormatFLAC, true
	case ".mp3":
		return FormatMP3, true
	default:
		return "", false
	}
}
