package merger_test

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"

	"MotionSwap/core/merger"
)

func buildTestJPEG() []byte {
	return []byte{
		0xFF, 0xD8, // SOI
		0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, // JFIF APP0
		0xFF, 0xD9, // EOI
	}
}

func buildTestMP4() []byte {
	ftypBox := []byte{
		0x00, 0x00, 0x00, 0x18, // box size
		'f', 't', 'y', 'p',
		'm', 'p', '4', '2',
		0x00, 0x00, 0x00, 0x00,
		'i', 's', 'o', 'm',
		'm', 'p', '4', '2',
	}
	return ftypBox
}

func TestMergeXiaomi(t *testing.T) {
	jpegData := buildTestJPEG()
	mp4Data := buildTestMP4()

	m := merger.NewMerger()
	result, err := m.Merge(jpegData, mp4Data, merger.BrandXiaomi)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(result) <= len(jpegData)+len(mp4Data) {
		t.Error("result should be larger than combined input (includes XMP)")
	}

	if !bytes.HasPrefix(result, jpegData[:2]) {
		t.Error("result should start with JPEG SOI")
	}
	if !bytes.HasSuffix(result, mp4Data) {
		t.Error("result should end with MP4 data")
	}
}

func TestMergeAndroid(t *testing.T) {
	jpegData := buildTestJPEG()
	mp4Data := buildTestMP4()

	m := merger.NewMerger()
	result, err := m.Merge(jpegData, mp4Data, merger.BrandAndroid)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(result) <= len(jpegData)+len(mp4Data) {
		t.Error("result should be larger than combined input")
	}
	if !bytes.HasPrefix(result, jpegData[:2]) {
		t.Error("result should start with JPEG SOI")
	}
	if !bytes.HasSuffix(result, mp4Data) {
		t.Error("result should end with MP4 data")
	}
}

func TestMergeHuawei(t *testing.T) {
	jpegData := buildTestJPEG()
	mp4Data := buildTestMP4()

	m := merger.NewMerger()
	result, err := m.Merge(jpegData, mp4Data, merger.BrandHuawei)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(result) != len(jpegData)+len(mp4Data) {
		t.Error("Huawei merge should just concatenate without XMP")
	}
	if !bytes.HasPrefix(result, jpegData[:2]) {
		t.Error("result should start with JPEG SOI")
	}
	if !bytes.HasSuffix(result, mp4Data) {
		t.Error("result should end with MP4 data")
	}
}

func TestMergeInvalidJPEG(t *testing.T) {
	m := merger.NewMerger()
	_, err := m.Merge([]byte{0x00, 0x01}, []byte{}, merger.BrandXiaomi)
	if err == nil {
		t.Fatal("expected error for invalid JPEG")
	}
}

func TestMergeEmptyVideo(t *testing.T) {
	m := merger.NewMerger()
	_, err := m.Merge(buildTestJPEG(), nil, merger.BrandAndroid)
	if err == nil {
		t.Fatal("expected error for empty video")
	}
}

func TestMergeRoundTrip_Xiaomi(t *testing.T) {
	jpegData := buildTestJPEG()
	mp4Data := buildTestMP4()

	m := merger.NewMerger()
	merged, err := m.Merge(jpegData, mp4Data, merger.BrandXiaomi)
	if err != nil {
		t.Fatalf("merge error: %v", err)
	}

	if !bytes.HasSuffix(merged, mp4Data) {
		t.Error("merged file should end with MP4 data")
	}

	imagePortion := merged[:len(merged)-len(mp4Data)]
	if len(imagePortion) <= len(jpegData) {
		t.Error("image portion should include XMP, not be smaller")
	}
}

func TestMergeRoundTrip_Android(t *testing.T) {
	jpegData := buildTestJPEG()
	mp4Data := buildTestMP4()

	m := merger.NewMerger()
	merged, err := m.Merge(jpegData, mp4Data, merger.BrandAndroid)
	if err != nil {
		t.Fatalf("merge error: %v", err)
	}

	if !bytes.HasSuffix(merged, mp4Data) {
		t.Error("merged file should end with MP4 data")
	}
}

func TestNewMerger_WithBrand(t *testing.T) {
	m := merger.NewMerger()

	_, err := m.Merge(buildTestJPEG(), buildTestMP4(), merger.BrandXiaomi)
	if err != nil {
		t.Errorf("Xiaomi merge: %v", err)
	}

	_, err = m.Merge(buildTestJPEG(), buildTestMP4(), merger.BrandAndroid)
	if err != nil {
		t.Errorf("Android merge: %v", err)
	}

	_, err = m.Merge(buildTestJPEG(), buildTestMP4(), merger.BrandHuawei)
	if err != nil {
		t.Errorf("Huawei merge: %v", err)
	}
}

func TestMergeInvalidBrand(t *testing.T) {
	m := merger.NewMerger()
	_, err := m.Merge(buildTestJPEG(), buildTestMP4(), merger.MotionPhotoBrand(99))
	if err == nil {
		t.Fatal("expected error for invalid brand")
	}
}

// TestMergeIntegration writes temp files and merges them
func TestMergeIntegration(t *testing.T) {
	dir := t.TempDir()
	jpgPath := filepath.Join(dir, "test.jpg")
	mp4Path := filepath.Join(dir, "test.mp4")

	if err := os.WriteFile(jpgPath, buildTestJPEG(), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(mp4Path, buildTestMP4(), 0644); err != nil {
		t.Fatal(err)
	}

	m := merger.NewMerger()
	result, err := m.MergeFiles(jpgPath, mp4Path, merger.BrandXiaomi)
	if err != nil {
		t.Fatalf("merge files: %v", err)
	}
	if len(result) == 0 {
		t.Fatal("expected non-empty result")
	}
}
