package xmp_test

import (
	"testing"

	"MotionSwap/pkg/xmp"
)

func TestBuildXMP_XiaomiFormat(t *testing.T) {
	fields := map[string]string{
		"GCamera:MicroVideo":                  "1",
		"GCamera:MicroVideoVersion":           "1",
		"GCamera:MicroVideoOffset":            "1234567",
		"GCamera:MicroVideoPresentationTimestampUs": "500000",
	}
	xmlData, err := xmp.BuildXMP(fields)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	parsed, err := xmp.ParseXMPXML(xmlData)
	if err != nil {
		t.Fatalf("unexpected parse error: %v", err)
	}

	for k, v := range fields {
		if parsed[k] != v {
			t.Errorf("field %q = %q, want %q", k, parsed[k], v)
		}
	}
}

func TestBuildXMP_AndroidGContainer(t *testing.T) {
	fields := map[string]string{
		"GCamera:MotionPhoto":                "1",
		"GCamera:MotionPhotoVersion":          "1",
		"GCamera:MotionPhotoPresentationTimestampUs": "500000",
		"GContainer:ItemLength":               "0, 1234567",
	}
	xmlData, err := xmp.BuildXMP(fields)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	parsed, err := xmp.ParseXMPXML(xmlData)
	if err != nil {
		t.Fatalf("unexpected parse error: %v", err)
	}

	for k, v := range fields {
		if parsed[k] != v {
			t.Errorf("field %q = %q, want %q", k, parsed[k], v)
		}
	}
}

func TestBuildXMP_EmptyFields(t *testing.T) {
	_, err := xmp.BuildXMP(nil)
	if err == nil {
		t.Fatal("expected error for nil fields")
	}

	_, err = xmp.BuildXMP(map[string]string{})
	if err == nil {
		t.Fatal("expected error for empty fields")
	}
}

func TestInjectXMP_Xiaomi(t *testing.T) {
	jpegData := buildTestJPEGWithoutXMP()
	fields := map[string]string{
		"GCamera:MicroVideo":                  "1",
		"GCamera:MicroVideoVersion":           "1",
		"GCamera:MicroVideoOffset":            "1234567",
		"GCamera:MicroVideoPresentationTimestampUs": "500000",
	}

	result, err := xmp.InjectXMP(jpegData, fields, 1234567)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(result) <= len(jpegData) {
		t.Error("injected data should be larger than original")
	}

	parsed, err := xmp.ExtractXMP(result)
	if err != nil {
		t.Fatalf("unexpected extract error: %v", err)
	}
	for k, v := range fields {
		if parsed[k] != v {
			t.Errorf("field %q = %q, want %q", k, parsed[k], v)
		}
	}
	if parsed["GCamera:MicroVideoOffset"] != "1234567" {
		t.Errorf("MicroVideoOffset preserved = %q", parsed["GCamera:MicroVideoOffset"])
	}
}

func TestInjectXMP_NoExistingAPP1(t *testing.T) {
	jpegData := []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00}
	fields := map[string]string{
		"GCamera:MicroVideo": "1",
	}
	result, err := xmp.InjectXMP(jpegData, fields, 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	parsed, err := xmp.ExtractXMP(result)
	if err != nil {
		t.Fatalf("unexpected extract error: %v", err)
	}
	if parsed["GCamera:MicroVideo"] != "1" {
		t.Errorf("MicroVideo = %q, want 1", parsed["GCamera:MicroVideo"])
	}
}

func buildTestJPEGWithoutXMP() []byte {
	return []byte{
		0xFF, 0xD8, // SOI
		0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, // JFIF APP0
		0xFF, 0xD9, // EOI
	}
}
