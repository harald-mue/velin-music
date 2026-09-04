package library

import (
	"context"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestParseMetadataFLAC(t *testing.T) {
	rootPath := t.TempDir()
	relative := "album/01-track.flac"
	path := filepath.Join(rootPath, filepath.FromSlash(relative))
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("create album directory: %v", err)
	}
	contents := testFLAC()
	if err := os.WriteFile(path, contents, 0o600); err != nil {
		t.Fatalf("write FLAC: %v", err)
	}

	root, err := ValidateRoot(rootPath)
	if err != nil {
		t.Fatalf("ValidateRoot() error = %v", err)
	}
	metadata, err := ParseMetadata(context.Background(), root, MediaFile{
		RelativePath: relative,
		Format:       FormatFLAC,
	})
	if err != nil {
		t.Fatalf("ParseMetadata() error = %v", err)
	}
	if metadata.Format != FormatFLAC || metadata.Title != "Track title" || metadata.Artist != "Artist" || metadata.AlbumArtist != "Album Artist" || metadata.Album != "Album" || metadata.Genre != "Electronic" {
		t.Fatalf("metadata = %+v", metadata)
	}
	if metadata.Year != 2024 || metadata.TrackNumber != 2 || metadata.TotalTracks != 10 {
		t.Fatalf("tag numbers = year %d, track %d/%d", metadata.Year, metadata.TrackNumber, metadata.TotalTracks)
	}
	if metadata.DiscNumber != 1 || metadata.TotalDiscs != 2 {
		t.Fatalf("disc numbers = %d/%d", metadata.DiscNumber, metadata.TotalDiscs)
	}
	if metadata.SampleRate != 44100 || metadata.BitsPerSample != 16 || metadata.Channels != 2 {
		t.Fatalf("technical metadata = %+v", metadata)
	}
	if metadata.Duration != 2*time.Second {
		t.Fatalf("duration = %v, want 2s", metadata.Duration)
	}
	if !metadata.HasArtwork || metadata.ArtworkMIME != "image/png" || len(metadata.Artwork) == 0 {
		t.Fatalf("artwork metadata = mime %q, bytes %d", metadata.ArtworkMIME, len(metadata.Artwork))
	}
}

func TestParseMetadataMP3(t *testing.T) {
	rootPath := t.TempDir()
	relative := "track.mp3"
	path := filepath.Join(rootPath, relative)
	contents := testMP3()
	if err := os.WriteFile(path, contents, 0o600); err != nil {
		t.Fatalf("write MP3: %v", err)
	}

	root, err := ValidateRoot(rootPath)
	if err != nil {
		t.Fatalf("ValidateRoot() error = %v", err)
	}
	metadata, err := ParseMetadata(context.Background(), root, MediaFile{
		RelativePath: relative,
		Format:       FormatMP3,
	})
	if err != nil {
		t.Fatalf("ParseMetadata() error = %v", err)
	}
	if metadata.Format != FormatMP3 || metadata.Title != "MP3 title" || metadata.Artist != "MP3 artist" || metadata.AlbumArtist != "MP3 album artist" || metadata.Album != "MP3 album" || metadata.Genre != "Rock" {
		t.Fatalf("metadata = %+v", metadata)
	}
	if metadata.Year != 2023 || metadata.TrackNumber != 3 || metadata.TotalTracks != 12 || metadata.DiscNumber != 1 || metadata.TotalDiscs != 1 {
		t.Fatalf("tag numbers = year %d, track %d/%d, disc %d/%d", metadata.Year, metadata.TrackNumber, metadata.TotalTracks, metadata.DiscNumber, metadata.TotalDiscs)
	}
	if metadata.SampleRate != 44100 || metadata.Channels != 2 {
		t.Fatalf("technical metadata = %+v", metadata)
	}
	if metadata.Duration != 2*time.Second+612*time.Millisecond {
		t.Fatalf("duration = %v, want 2.612s", metadata.Duration)
	}
}

func TestParseMetadataUsesFilenameFallbackAndRejectsUnsafePath(t *testing.T) {
	rootPath := t.TempDir()
	path := filepath.Join(rootPath, "untagged.mp3")
	if err := os.WriteFile(path, testMP3WithoutTags(), 0o600); err != nil {
		t.Fatalf("write MP3: %v", err)
	}
	root, err := ValidateRoot(rootPath)
	if err != nil {
		t.Fatalf("ValidateRoot() error = %v", err)
	}
	metadata, err := ParseMetadata(context.Background(), root, MediaFile{RelativePath: "untagged.mp3", Format: FormatMP3})
	if err != nil {
		t.Fatalf("ParseMetadata() error = %v", err)
	}
	if metadata.Title != "untagged" {
		t.Fatalf("fallback title = %q, want %q", metadata.Title, "untagged")
	}

	if _, err := ParseMetadata(context.Background(), root, MediaFile{RelativePath: "../untagged.mp3", Format: FormatMP3}); err == nil {
		t.Fatal("ParseMetadata() error = nil for escaping path")
	}
}

func TestParseMetadataRejectsMalformedAudio(t *testing.T) {
	rootPath := t.TempDir()
	if err := os.WriteFile(filepath.Join(rootPath, "broken.flac"), []byte("not flac"), 0o600); err != nil {
		t.Fatalf("write malformed file: %v", err)
	}
	root, err := ValidateRoot(rootPath)
	if err != nil {
		t.Fatalf("ValidateRoot() error = %v", err)
	}
	if _, err := ParseMetadata(context.Background(), root, MediaFile{RelativePath: "broken.flac", Format: FormatFLAC}); err == nil {
		t.Fatal("ParseMetadata() error = nil for malformed FLAC")
	}
}

func TestParseMetadataRejectsFileChangedSinceDiscovery(t *testing.T) {
	rootPath := t.TempDir()
	path := filepath.Join(rootPath, "changed.mp3")
	if err := os.WriteFile(path, testMP3WithoutTags(), 0o600); err != nil {
		t.Fatalf("write MP3: %v", err)
	}
	root, err := ValidateRoot(rootPath)
	if err != nil {
		t.Fatalf("ValidateRoot() error = %v", err)
	}
	if _, err := ParseMetadata(context.Background(), root, MediaFile{
		RelativePath: "changed.mp3",
		Format:       FormatMP3,
		Size:         1,
	}); err == nil {
		t.Fatal("ParseMetadata() error = nil for changed file identity")
	}
}

func testFLAC() []byte {
	streamInfo := make([]byte, 34)
	binary.BigEndian.PutUint16(streamInfo[0:2], 4096)
	binary.BigEndian.PutUint16(streamInfo[2:4], 4096)
	value := uint64(44100)<<44 | uint64(2-1)<<41 | uint64(16-1)<<36 | uint64(88200)
	binary.BigEndian.PutUint64(streamInfo[10:18], value)

	commentValues := []string{
		"TITLE=Track title",
		"ARTIST=Artist",
		"ALBUMARTIST=Album Artist",
		"ALBUM=Album",
		"GENRE=Electronic",
		"DATE=2024",
		"TRACKNUMBER=2",
		"TRACKTOTAL=10",
		"DISCNUMBER=1",
		"DISCTOTAL=2",
	}
	comment := make([]byte, 4)
	vendor := []byte("velin-test")
	comment = append(comment, vendor...)
	binary.LittleEndian.PutUint32(comment[0:4], uint32(len(vendor)))
	var count [4]byte
	binary.LittleEndian.PutUint32(count[:], uint32(len(commentValues)))
	comment = append(comment, count[:]...)
	for _, value := range commentValues {
		var length [4]byte
		binary.LittleEndian.PutUint32(length[:], uint32(len(value)))
		comment = append(comment, length[:]...)
		comment = append(comment, value...)
	}

	pictureData := testPNGImage(1, 1)
	picture := make([]byte, 0)
	var length [4]byte
	binary.BigEndian.PutUint32(length[:], 3)
	picture = append(picture, length[:]...)
	binary.BigEndian.PutUint32(length[:], 9)
	picture = append(picture, length[:]...)
	picture = append(picture, "image/png"...)
	binary.BigEndian.PutUint32(length[:], 0)
	picture = append(picture, length[:]...)
	for _, value := range []uint32{1, 1, 8, 0, uint32(len(pictureData))} {
		binary.BigEndian.PutUint32(length[:], value)
		picture = append(picture, length[:]...)
	}
	picture = append(picture, pictureData...)

	result := []byte("fLaC")
	result = append(result, 0x00, 0x00, 0x00, 0x22)
	result = append(result, streamInfo...)
	result = append(result, 0x06, byte(len(picture)>>16), byte(len(picture)>>8), byte(len(picture)))
	result = append(result, picture...)
	result = append(result, 0x84, byte(len(comment)>>16), byte(len(comment)>>8), byte(len(comment)))
	result = append(result, comment...)
	return result
}

func testMP3() []byte {
	frames := id3v23Frames(map[string]string{
		"TIT2": "MP3 title",
		"TPE1": "MP3 artist",
		"TPE2": "MP3 album artist",
		"TALB": "MP3 album",
		"TCON": "Rock",
		"TYER": "2023",
		"TRCK": "3/12",
		"TPOS": "1/1",
	})
	frame := mp3Frame()
	return append(frames, frame...)
}

func testMP3WithoutTags() []byte {
	return mp3Frame()
}

func id3v23Frames(values map[string]string) []byte {
	body := make([]byte, 0)
	for _, id := range []string{"TIT2", "TPE1", "TPE2", "TALB", "TCON", "TYER", "TRCK", "TPOS"} {
		value := append([]byte{0}, []byte(values[id])...)
		frame := make([]byte, 10)
		copy(frame[:4], id)
		binary.BigEndian.PutUint32(frame[4:8], uint32(len(value)))
		body = append(body, frame...)
		body = append(body, value...)
	}
	header := []byte{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0}
	size := len(body)
	for index := 9; index >= 6; index-- {
		header[index] = byte(size & 0x7f)
		size >>= 7
	}
	return append(header, body...)
}

func mp3Frame() []byte {
	const frameLength = 417
	frame := make([]byte, frameLength)
	copy(frame[:4], []byte{0xff, 0xfb, 0x90, 0x00})
	copy(frame[36:40], []byte("Xing"))
	frame[43] = 1 // Xing flags: frames field is present.
	binary.BigEndian.PutUint32(frame[44:48], 100)
	return frame
}
