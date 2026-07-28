package mp4_test

import (
	"encoding/binary"
	"testing"

	"MotionSwap/pkg/mp4"
)

func buildMP4FTYPBox(majorBrand string, minorVersion uint32, compatibleBrands ...string) []byte {
	content := []byte(majorBrand)
	if len(content) < 4 {
		padding := make([]byte, 4-len(content))
		content = append(content, padding...)
	}
	content = binary.BigEndian.AppendUint32(content, minorVersion)
	for _, b := range compatibleBrands {
		cb := []byte(b)
		if len(cb) < 4 {
			padding := make([]byte, 4-len(cb))
			cb = append(cb, padding...)
		}
		content = append(content, cb[:4]...)
	}
	boxSize := 8 + len(content)
	box := make([]byte, 8)
	binary.BigEndian.PutUint32(box[0:4], uint32(boxSize))
	copy(box[4:8], "ftyp")
	return append(box, content...)
}

func buildMinimalMP4() []byte {
	ftyp := buildMP4FTYPBox("mp42", 0, "isom", "mp42")
	moov := []byte{
		0x00, 0x00, 0x00, 0x08, // box size 8
		'm', 'o', 'o', 'v', // box type
	}
	data := make([]byte, 0, len(ftyp)+len(moov))
	data = append(data, ftyp...)
	data = append(data, moov...)
	return data
}

func TestFindFTYPBox_Found(t *testing.T) {
	mp4Data := buildMinimalMP4()
	info, err := mp4.FindFTYPBox(mp4Data)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info == nil {
		t.Fatal("expected non-nil info")
	}
	if info.Offset != 0 {
		t.Errorf("expected offset 0 (start of data), got %d", info.Offset)
	}
	if info.MajorBrand != "mp42" {
		t.Errorf("major brand = %q, want mp42", info.MajorBrand)
	}
}

func TestFindFTYPBox_NotFound(t *testing.T) {
	data := []byte{0x00, 0x00, 0x00, 0x08, 't', 'e', 's', 't'}
	info, err := mp4.FindFTYPBox(data)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info != nil {
		t.Fatal("expected nil for non-MP4 data")
	}
}

func TestFindFTYPBox_EmptyData(t *testing.T) {
	_, err := mp4.FindFTYPBox(nil)
	if err == nil {
		t.Fatal("expected error for nil data")
	}
}

func TestFindFTYPBox_TruncatedBox(t *testing.T) {
	data := []byte{0x00, 0x00, 0x00, 0x0C, 'f', 't', 'y', 'p', 'm', 'p', '4'}
	info, err := mp4.FindFTYPBox(data)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info != nil {
		t.Fatal("expected nil for truncated ftyp box")
	}
}

func TestFindFTYPBox_WithJPEGPrefix(t *testing.T) {
	jpegHeader := []byte{0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x10}
	mp4Data := buildMinimalMP4()
	data := append(jpegHeader, mp4Data...)

	info, err := mp4.FindFTYPBox(data)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info == nil {
		t.Fatal("expected non-nil info")
	}
	if info.Offset != len(jpegHeader) {
		t.Errorf("offset = %d, want %d", info.Offset, len(jpegHeader))
	}
}

func TestIsMP4(t *testing.T) {
	if !mp4.IsMP4(buildMinimalMP4()) {
		t.Error("expected MP4 data to be detected")
	}
	if mp4.IsMP4([]byte{0xFF, 0xD8, 0xFF}) {
		t.Error("expected JPEG data to not be detected as MP4")
	}
	if mp4.IsMP4(nil) {
		t.Error("expected nil to not be detected as MP4")
	}
}
