package library

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	_ "golang.org/x/image/webp"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	"os"
	"path/filepath"
	"sort"

	xdraw "golang.org/x/image/draw"
)

const (
	maxArtworkVariantBytes      = 4 << 20
	maxArtworkVariantCacheBytes = 512 << 20
	maxArtworkVariantCacheFiles = 4_096
)

var artworkVariantSizes = map[int]struct{}{
	128: {},
	256: {},
	512: {},
}

// IsArtworkVariantSize reports whether size is an exposed bounded thumbnail size.
func IsArtworkVariantSize(size int) bool {
	_, ok := artworkVariantSizes[size]
	return ok
}

// OpenVariant opens or creates a bounded JPEG derivative for an indexed cover.
func (r *CoverReader) OpenVariant(ctx context.Context, id string, size int) (*os.File, CoverFile, error) {
	if !IsArtworkVariantSize(size) {
		return nil, CoverFile{}, errors.New("artwork variant size is not supported")
	}
	if r == nil || r.cache == nil || r.cache.variantDirectory == "" {
		return nil, CoverFile{}, errors.New("cover reader is not configured")
	}
	original, content, err := r.Open(ctx, id)
	if err != nil {
		return nil, CoverFile{}, err
	}
	defer original.Close()
	if !isCoverChecksum(id) {
		return nil, CoverFile{}, errors.New("cover ID is invalid")
	}

	path := filepath.Join(r.cache.variantDirectory, fmt.Sprintf("%s-%d.jpg", id, size))
	if file, metadata, err := openArtworkVariant(path, size); err == nil {
		metadata.ModTime = content.ModTime
		return file, metadata, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, CoverFile{}, err
	}

	select {
	case r.cache.variantGeneration <- struct{}{}:
		defer func() { <-r.cache.variantGeneration }()
	case <-ctx.Done():
		return nil, CoverFile{}, ctx.Err()
	}
	if file, metadata, err := openArtworkVariant(path, size); err == nil {
		metadata.ModTime = content.ModTime
		return file, metadata, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, CoverFile{}, err
	}
	if _, err := original.Seek(0, 0); err != nil {
		return nil, CoverFile{}, fmt.Errorf("seek original artwork: %w", err)
	}
	if err := createArtworkVariant(ctx, original, r.cache.variantDirectory, path, size); err != nil {
		return nil, CoverFile{}, err
	}
	file, metadata, err := openArtworkVariant(path, size)
	if err != nil {
		return nil, CoverFile{}, err
	}
	metadata.ModTime = content.ModTime
	return file, metadata, nil
}

func createArtworkVariant(
	ctx context.Context,
	source *os.File,
	directory string,
	path string,
	size int,
) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	decoded, _, err := image.Decode(source)
	if err != nil {
		return fmt.Errorf("decode artwork variant source: %w", err)
	}
	bounds := decoded.Bounds()
	width, height := scaledArtworkDimensions(bounds.Dx(), bounds.Dy(), size)
	if width < 1 || height < 1 {
		return errors.New("artwork variant source dimensions are invalid")
	}
	destination := image.NewRGBA(image.Rect(0, 0, width, height))
	draw.Draw(destination, destination.Bounds(), &image.Uniform{C: color.RGBA{R: 17, G: 18, B: 20, A: 255}}, image.Point{}, draw.Src)
	xdraw.ApproxBiLinear.Scale(destination, destination.Bounds(), decoded, bounds, draw.Over, nil)
	if err := ctx.Err(); err != nil {
		return err
	}

	temporary, err := os.CreateTemp(directory, ".variant-*")
	if err != nil {
		return fmt.Errorf("create artwork variant: %w", err)
	}
	temporaryPath := temporary.Name()
	installed := false
	defer func() {
		_ = temporary.Close()
		if !installed {
			_ = os.Remove(temporaryPath)
		}
	}()
	if err := temporary.Chmod(0o600); err != nil {
		return fmt.Errorf("set artwork variant permissions: %w", err)
	}
	if err := jpeg.Encode(temporary, destination, &jpeg.Options{Quality: 85}); err != nil {
		return fmt.Errorf("encode artwork variant: %w", err)
	}
	info, err := temporary.Stat()
	if err != nil {
		return fmt.Errorf("inspect artwork variant: %w", err)
	}
	if info.Size() < 1 || info.Size() > maxArtworkVariantBytes {
		return errors.New("generated artwork variant has an invalid size")
	}
	if err := temporary.Sync(); err != nil {
		return fmt.Errorf("sync artwork variant: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close artwork variant: %w", err)
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		return fmt.Errorf("install artwork variant: %w", err)
	}
	installed = true
	if err := trimArtworkVariantCache(directory, maxArtworkVariantCacheFiles, maxArtworkVariantCacheBytes); err != nil {
		return err
	}
	return nil
}

func trimArtworkVariantCache(directory string, maximumFiles int, maximumBytes int64) error {
	type candidate struct {
		path    string
		size    int64
		modTime int64
	}
	entries, err := os.ReadDir(directory)
	if err != nil {
		return fmt.Errorf("read artwork variant cache: %w", err)
	}
	candidates := make([]candidate, 0, len(entries))
	var totalBytes int64
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			return fmt.Errorf("inspect artwork variant cache entry: %w", err)
		}
		if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
			continue
		}
		candidates = append(candidates, candidate{
			path:    filepath.Join(directory, entry.Name()),
			size:    info.Size(),
			modTime: info.ModTime().UnixNano(),
		})
		totalBytes += info.Size()
	}
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].modTime < candidates[j].modTime })
	for len(candidates) > maximumFiles || totalBytes > maximumBytes {
		oldest := candidates[0]
		candidates = candidates[1:]
		if err := os.Remove(oldest.path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("trim artwork variant cache: %w", err)
		}
		totalBytes -= oldest.size
	}
	return nil
}

func openArtworkVariant(path string, maximumDimension int) (*os.File, CoverFile, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, CoverFile{}, err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > maxArtworkVariantBytes {
		return nil, CoverFile{}, errors.New("artwork variant cache entry is invalid")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, CoverFile{}, fmt.Errorf("open artwork variant: %w", err)
	}
	config, format, err := image.DecodeConfig(file)
	if err != nil || format != "jpeg" || config.Width < 1 || config.Height < 1 ||
		config.Width > maximumDimension || config.Height > maximumDimension {
		_ = file.Close()
		return nil, CoverFile{}, errors.New("artwork variant cache entry is invalid")
	}
	if _, err := file.Seek(0, 0); err != nil {
		_ = file.Close()
		return nil, CoverFile{}, fmt.Errorf("seek artwork variant: %w", err)
	}
	return file, CoverFile{MIMEType: "image/jpeg", ModTime: info.ModTime()}, nil
}

func scaledArtworkDimensions(width, height, maximum int) (int, int) {
	if width < 1 || height < 1 || maximum < 1 {
		return 0, 0
	}
	if width <= maximum && height <= maximum {
		return width, height
	}
	if width >= height {
		return maximum, max(1, height*maximum/width)
	}
	return max(1, width*maximum/height), maximum
}

func isCoverChecksum(id string) bool {
	if len(id) != 64 {
		return false
	}
	_, err := hex.DecodeString(id)
	return err == nil
}
