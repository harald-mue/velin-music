package library

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/dhowden/tag"
	flac "github.com/mewkiz/flac"
)

const maxTagBytes = 8 << 20

// TrackMetadata contains normalized tags and technical properties extracted
// without decoding audio frames.
type TrackMetadata struct {
	Format        Format
	Title         string
	Artist        string
	AlbumArtist   string
	Album         string
	Genre         string
	Year          int
	TrackNumber   int
	TotalTracks   int
	DiscNumber    int
	TotalDiscs    int
	Duration      time.Duration
	SampleRate    int
	BitsPerSample int
	Channels      int
	HasArtwork    bool
	ArtworkMIME   string
	Artwork       []byte
}

// ParseMetadata opens a discovered file through the validated root and reads
// tags plus stream headers. It never decodes audio frames or writes to disk.
func ParseMetadata(ctx context.Context, root Root, media MediaFile) (TrackMetadata, error) {
	if err := ctx.Err(); err != nil {
		return TrackMetadata{}, err
	}
	path, err := safeMediaPath(root, media.RelativePath)
	if err != nil {
		return TrackMetadata{}, err
	}

	file, err := os.Open(path)
	if err != nil {
		return TrackMetadata{}, fmt.Errorf("open media file: %w", err)
	}
	defer file.Close()

	info, err := file.Stat()
	if err != nil {
		return TrackMetadata{}, fmt.Errorf("stat media file: %w", err)
	}
	pathInfo, err := os.Lstat(path)
	if err != nil {
		return TrackMetadata{}, fmt.Errorf("recheck media file: %w", err)
	}
	if pathInfo.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() || !pathInfo.Mode().IsRegular() || !os.SameFile(info, pathInfo) {
		return TrackMetadata{}, errors.New("media file changed or is not a regular file")
	}
	if media.Size > 0 && info.Size() != media.Size {
		return TrackMetadata{}, errors.New("media file changed since discovery")
	}
	if !media.ModifiedAt.IsZero() && !info.ModTime().Equal(media.ModifiedAt) {
		return TrackMetadata{}, errors.New("media file changed since discovery")
	}

	metadata := TrackMetadata{
		Format: media.Format,
		Title:  fallbackTitle(media.RelativePath),
	}
	tags, err := readTags(file)
	if err != nil {
		return TrackMetadata{}, fmt.Errorf("read media tags: %w", err)
	}
	if tags != nil {
		metadata.Title = firstNonEmpty(tags.Title(), metadata.Title)
		metadata.Artist = strings.TrimSpace(tags.Artist())
		metadata.AlbumArtist = strings.TrimSpace(tags.AlbumArtist())
		metadata.Album = strings.TrimSpace(tags.Album())
		metadata.Genre = strings.TrimSpace(tags.Genre())
		metadata.Year = tags.Year()
		metadata.TrackNumber, metadata.TotalTracks = tags.Track()
		metadata.DiscNumber, metadata.TotalDiscs = tags.Disc()
		if picture := tags.Picture(); picture != nil {
			metadata.HasArtwork = len(picture.Data) > 0
			metadata.ArtworkMIME = strings.TrimSpace(picture.MIMEType)
			metadata.Artwork = append([]byte(nil), picture.Data...)
		}
	}

	if err := ctx.Err(); err != nil {
		return TrackMetadata{}, err
	}
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return TrackMetadata{}, fmt.Errorf("rewind media file: %w", err)
	}
	var technical TrackMetadata
	switch media.Format {
	case FormatFLAC:
		technical, err = parseFLACTechnical(file)
	case FormatMP3:
		technical, err = parseMP3Technical(file, info.Size())
	default:
		return TrackMetadata{}, fmt.Errorf("unsupported media format %q", media.Format)
	}
	if err != nil {
		return TrackMetadata{}, fmt.Errorf("read %s stream properties: %w", media.Format, err)
	}
	metadata.Duration = technical.Duration
	metadata.SampleRate = technical.SampleRate
	metadata.BitsPerSample = technical.BitsPerSample
	metadata.Channels = technical.Channels
	return metadata, nil
}

func readTags(file *os.File) (tag.Metadata, error) {
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	reader := &limitedReadSeeker{ReadSeeker: file, remaining: maxTagBytes}
	metadata, err := tag.ReadFrom(reader)
	if errors.Is(err, tag.ErrNoTagsFound) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return metadata, nil
}

func parseFLACTechnical(file io.ReadSeeker) (TrackMetadata, error) {
	reader := &limitedReadSeeker{ReadSeeker: file, remaining: maxTagBytes}
	stream, err := flac.NewSeek(reader)
	if err != nil {
		return TrackMetadata{}, err
	}
	info := stream.Info
	if info == nil || info.SampleRate == 0 || info.NChannels == 0 || info.BitsPerSample == 0 {
		return TrackMetadata{}, errors.New("FLAC stream info is incomplete")
	}
	return TrackMetadata{
		Duration:      durationFromSamples(info.NSamples, uint64(info.SampleRate)),
		SampleRate:    int(info.SampleRate),
		BitsPerSample: int(info.BitsPerSample),
		Channels:      int(info.NChannels),
	}, nil
}

func parseMP3Technical(file *os.File, fileSize int64) (TrackMetadata, error) {
	frameOffset, header, err := findMP3Frame(file, fileSize)
	if err != nil {
		return TrackMetadata{}, err
	}

	var duration time.Duration
	if frames, ok := readXingFrameCount(file, frameOffset, header); ok {
		duration = durationFromSamples(uint64(frames)*uint64(header.samplesPerFrame), uint64(header.sampleRate))
	} else if frames, ok := readVBRIFrameCount(file, frameOffset, header); ok {
		duration = durationFromSamples(uint64(frames)*uint64(header.samplesPerFrame), uint64(header.sampleRate))
	} else if header.bitrateKbps > 0 && fileSize > frameOffset {
		// This is an estimate for files without a VBR frame-count header.
		duration = time.Duration((fileSize-frameOffset)*8/int64(header.bitrateKbps)) * time.Millisecond
	}

	return TrackMetadata{
		Duration:   duration,
		SampleRate: header.sampleRate,
		Channels:   header.channels,
	}, nil
}

type mp3FrameHeader struct {
	version         int
	layer           int
	bitrateKbps     int
	sampleRate      int
	channels        int
	samplesPerFrame int
	frameLength     int
}

func findMP3Frame(file *os.File, fileSize int64) (int64, mp3FrameHeader, error) {
	if fileSize < 4 {
		return 0, mp3FrameHeader{}, errors.New("MP3 file is too small")
	}
	start := int64(0)
	if header := make([]byte, 10); fileSize >= int64(len(header)) {
		if _, err := file.ReadAt(header, 0); err == nil && string(header[:3]) == "ID3" {
			size, ok := syncSafeInt(header[6:10])
			if !ok {
				return 0, mp3FrameHeader{}, errors.New("invalid MP3 ID3v2 size")
			}
			start = int64(10 + size)
			if header[5]&0x10 != 0 {
				start += 10
			}
		}
	}
	if start >= fileSize {
		return 0, mp3FrameHeader{}, errors.New("MP3 contains no audio frame")
	}

	const scanLimit = int64(1 << 20)
	length := fileSize - start
	if length > scanLimit {
		length = scanLimit
	}
	buffer := make([]byte, length)
	n, err := file.ReadAt(buffer, start)
	if err != nil && !errors.Is(err, io.EOF) {
		return 0, mp3FrameHeader{}, fmt.Errorf("read MP3 frame header: %w", err)
	}
	buffer = buffer[:n]
	for i := 0; i+4 <= len(buffer); i++ {
		header, ok := decodeMP3FrameHeader(buffer[i : i+4])
		if !ok {
			continue
		}
		if int64(header.frameLength) > fileSize-(start+int64(i)) {
			continue
		}
		return start + int64(i), header, nil
	}
	return 0, mp3FrameHeader{}, errors.New("MP3 audio frame not found in scan window")
}

func decodeMP3FrameHeader(header []byte) (mp3FrameHeader, bool) {
	if len(header) < 4 || header[0] != 0xff || header[1]&0xe0 != 0xe0 {
		return mp3FrameHeader{}, false
	}
	versionBits := (header[1] >> 3) & 0x03
	layerBits := (header[1] >> 1) & 0x03
	if versionBits == 1 || layerBits != 1 { // MPEG audio Layer III only.
		return mp3FrameHeader{}, false
	}
	bitrateIndex := (header[2] >> 4) & 0x0f
	sampleIndex := (header[2] >> 2) & 0x03
	if bitrateIndex == 0 || bitrateIndex == 15 || sampleIndex == 3 {
		return mp3FrameHeader{}, false
	}

	version := 1
	bitrates := []int{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0}
	sampleRates := []int{44100, 48000, 32000}
	samplesPerFrame := 1152
	if versionBits == 2 { // MPEG-2
		version = 2
		bitrates = []int{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
		sampleRates = []int{22050, 24000, 16000}
		samplesPerFrame = 576
	} else if versionBits == 0 { // MPEG-2.5
		version = 25
		bitrates = []int{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
		sampleRates = []int{11025, 12000, 8000}
		samplesPerFrame = 576
	}
	bitrate := bitrates[bitrateIndex]
	sampleRate := sampleRates[sampleIndex]
	padding := int((header[2] >> 1) & 0x01)
	frameLength := 144*bitrate*1000/sampleRate + padding
	if version != 1 {
		frameLength = 72*bitrate*1000/sampleRate + padding
	}
	if frameLength < 5 {
		return mp3FrameHeader{}, false
	}
	channels := 2
	if header[3]>>6 == 3 {
		channels = 1
	}
	return mp3FrameHeader{
		version:         version,
		layer:           3,
		bitrateKbps:     bitrate,
		sampleRate:      sampleRate,
		channels:        channels,
		samplesPerFrame: samplesPerFrame,
		frameLength:     frameLength,
	}, true
}

func readXingFrameCount(file *os.File, frameOffset int64, header mp3FrameHeader) (uint32, bool) {
	sideInfo := 17
	if header.version == 1 {
		sideInfo = 17
		if header.channels == 2 {
			sideInfo = 32
		}
	} else if header.channels == 2 {
		sideInfo = 17
	}
	var data [12]byte
	if _, err := file.ReadAt(data[:], frameOffset+4+int64(sideInfo)); err != nil {
		return 0, false
	}
	if string(data[:4]) != "Xing" && string(data[:4]) != "Info" {
		return 0, false
	}
	if data[7]&0x01 == 0 {
		return 0, false
	}
	frames := binary.BigEndian.Uint32(data[8:12])
	return frames, frames > 0
}

func readVBRIFrameCount(file *os.File, frameOffset int64, header mp3FrameHeader) (uint32, bool) {
	var data [18]byte
	if _, err := file.ReadAt(data[:], frameOffset+36); err != nil {
		return 0, false
	}
	if string(data[:4]) != "VBRI" {
		return 0, false
	}
	frames := binary.BigEndian.Uint32(data[14:18])
	return frames, frames > 0
}

func durationFromSamples(samples, sampleRate uint64) time.Duration {
	if sampleRate == 0 {
		return 0
	}
	milliseconds := (samples*1000 + sampleRate/2) / sampleRate
	max := uint64((1<<63 - 1) / uint64(time.Millisecond))
	if milliseconds > max {
		return time.Duration(1<<63 - 1)
	}
	return time.Duration(milliseconds) * time.Millisecond
}

func syncSafeInt(value []byte) (int, bool) {
	if len(value) != 4 {
		return 0, false
	}
	if value[0]&0x80 != 0 || value[1]&0x80 != 0 || value[2]&0x80 != 0 || value[3]&0x80 != 0 {
		return 0, false
	}
	return int(value[0])<<21 | int(value[1])<<14 | int(value[2])<<7 | int(value[3]), true
}

func safeMediaPath(root Root, relative string) (string, error) {
	if root.Path == "" {
		return "", errors.New("media root path must not be empty")
	}
	if relative == "" || filepath.IsAbs(filepath.FromSlash(relative)) {
		return "", errors.New("media path must be relative")
	}
	clean := filepath.Clean(filepath.FromSlash(relative))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", errors.New("media path escapes library root")
	}
	path := filepath.Join(root.Path, clean)
	entry, err := os.Lstat(path)
	if err != nil {
		return "", fmt.Errorf("inspect media path: %w", err)
	}
	if entry.Mode()&os.ModeSymlink != 0 {
		return "", errors.New("media path must not be a symlink")
	}
	canonical, err := filepath.EvalSymlinks(path)
	if err != nil {
		return "", fmt.Errorf("canonicalize media path: %w", err)
	}
	canonicalRoot, err := filepath.EvalSymlinks(root.Path)
	if err != nil {
		return "", fmt.Errorf("canonicalize media root: %w", err)
	}
	relativeCanonical, err := filepath.Rel(canonicalRoot, canonical)
	if err != nil || relativeCanonical == ".." || strings.HasPrefix(relativeCanonical, ".."+string(filepath.Separator)) {
		return "", errors.New("media path escapes library root")
	}
	return path, nil
}

func fallbackTitle(relative string) string {
	name := filepath.Base(filepath.FromSlash(relative))
	extension := filepath.Ext(name)
	name = strings.TrimSuffix(name, extension)
	name = strings.TrimSpace(name)
	if name == "" {
		return "Untitled"
	}
	return name
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value = strings.TrimSpace(value); value != "" {
			return value
		}
	}
	return ""
}

type limitedReadSeeker struct {
	io.ReadSeeker
	remaining int64
}

func (r *limitedReadSeeker) Read(p []byte) (int, error) {
	if r.remaining <= 0 {
		return 0, errors.New("metadata exceeds size limit")
	}
	if int64(len(p)) > r.remaining {
		p = p[:r.remaining]
	}
	n, err := r.ReadSeeker.Read(p)
	r.remaining -= int64(n)
	if err == nil && r.remaining == 0 {
		return n, errors.New("metadata exceeds size limit")
	}
	return n, err
}
