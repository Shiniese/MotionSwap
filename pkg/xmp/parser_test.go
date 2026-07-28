package xmp_test

import (
	"testing"

	"MotionSwap/pkg/xmp"
)

func TestExtractXMPFromJPEG_WithXiaomiMetadata(t *testing.T) {
	data := buildTestJPEGWithXMP(`<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:GCamera="http://ns.google.com/photos/1.0/camera/">
      <GCamera:MicroVideo>1</GCamera:MicroVideo>
      <GCamera:MicroVideoVersion>1</GCamera:MicroVideoVersion>
      <GCamera:MicroVideoOffset>1234567</GCamera:MicroVideoOffset>
      <GCamera:MicroVideoPresentationTimestampUs>500000</GCamera:MicroVideoPresentationTimestampUs>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>`)

	result, err := xmp.ExtractXMP(data)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result == nil {
		t.Fatal("expected non-nil result")
	}
	if result["GCamera:MicroVideo"] != "1" {
		t.Errorf("MicroVideo = %q, want 1", result["GCamera:MicroVideo"])
	}
	if result["GCamera:MicroVideoOffset"] != "1234567" {
		t.Errorf("MicroVideoOffset = %q, want 1234567", result["GCamera:MicroVideoOffset"])
	}
	if result["GCamera:MicroVideoPresentationTimestampUs"] != "500000" {
		t.Errorf("MicroVideoPresentationTimestampUs = %q, want 500000", result["GCamera:MicroVideoPresentationTimestampUs"])
	}
}

func TestExtractXMPFromJPEG_WithAndroidGContainer(t *testing.T) {
	data := buildTestJPEGWithXMP(`<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:GContainer="http://ns.google.com/photos/1.0/container/"
      xmlns:GCamera="http://ns.google.com/photos/1.0/camera/">
      <GContainer:ItemLength>0, 1234567</GContainer:ItemLength>
      <GCamera:MotionPhoto>1</GCamera:MotionPhoto>
      <GCamera:MotionPhotoVersion>1</GCamera:MotionPhotoVersion>
      <GCamera:MotionPhotoPresentationTimestampUs>500000</GCamera:MotionPhotoPresentationTimestampUs>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>`)

	result, err := xmp.ExtractXMP(data)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result == nil {
		t.Fatal("expected non-nil result")
	}
	if result["GContainer:ItemLength"] != "0, 1234567" {
		t.Errorf("ItemLength = %q, want 0, 1234567", result["GContainer:ItemLength"])
	}
	if result["GCamera:MotionPhoto"] != "1" {
		t.Errorf("MotionPhoto = %q, want 1", result["GCamera:MotionPhoto"])
	}
}

func TestExtractXMPFromJPEG_NoXMP(t *testing.T) {
	data := []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00}
	result, err := xmp.ExtractXMP(data)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result != nil {
		t.Fatal("expected nil result for JPEG without XMP")
	}
}

func TestExtractXMPFromJPEG_EmptyData(t *testing.T) {
	_, err := xmp.ExtractXMP(nil)
	if err == nil {
		t.Fatal("expected error for nil data")
	}
}

func TestExtractXMPFromJPEG_TruncatedData(t *testing.T) {
	data := []byte{0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x00}
	_, err := xmp.ExtractXMP(data)
	if err == nil {
		t.Fatal("expected error for truncated data")
	}
}

func TestParseXMPXML_Simple(t *testing.T) {
	xml := `<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:GCamera="http://ns.google.com/photos/1.0/camera/">
      <GCamera:MicroVideo>1</GCamera:MicroVideo>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>`

	result, err := xmp.ParseXMPXML([]byte(xml))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result["GCamera:MicroVideo"] != "1" {
		t.Errorf("MicroVideo = %q, want 1", result["GCamera:MicroVideo"])
	}
}

func TestParseXMPXML_MultipleFields(t *testing.T) {
	xml := `<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
      xmlns:GContainer="http://ns.google.com/photos/1.0/container/">
      <GCamera:MicroVideo>1</GCamera:MicroVideo>
      <GCamera:MicroVideoOffset>98765</GCamera:MicroVideoOffset>
      <GContainer:ItemLength>0, 98765</GContainer:ItemLength>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>`

	result, err := xmp.ParseXMPXML([]byte(xml))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result["GCamera:MicroVideo"] != "1" {
		t.Errorf("MicroVideo = %q, want 1", result["GCamera:MicroVideo"])
	}
	if result["GCamera:MicroVideoOffset"] != "98765" {
		t.Errorf("MicroVideoOffset = %q, want 98765", result["GCamera:MicroVideoOffset"])
	}
	if result["GContainer:ItemLength"] != "0, 98765" {
		t.Errorf("ItemLength = %q, want 0, 98765", result["GContainer:ItemLength"])
	}
}

func TestParseXMPXML_DirectoryItemFormat(t *testing.T) {
	xml := `<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
      xmlns:GContainer="http://ns.google.com/photos/1.0/container/"
      GCamera:MotionPhoto="1"
      GCamera:MotionPhotoPresentationTimestampUs="500000"
      GContainer:ItemLength="0, 1234567"
      GContainer:ItemPadding="0, 0"/>
  </rdf:RDF>
</x:xmpmeta>`

	result, err := xmp.ParseXMPXML([]byte(xml))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result["GCamera:MotionPhoto"] != "1" {
		t.Errorf("MotionPhoto = %q", result["GCamera:MotionPhoto"])
	}
	if result["GContainer:ItemLength"] != "0, 1234567" {
		t.Errorf("GContainer:ItemLength = %q", result["GContainer:ItemLength"])
	}
	if result["GContainer:ItemPadding"] != "0, 0" {
		t.Errorf("GContainer:ItemPadding = %q", result["GContainer:ItemPadding"])
	}
}

func TestParseXMPXML_EmptyXML(t *testing.T) {
	_, err := xmp.ParseXMPXML(nil)
	if err == nil {
		t.Fatal("expected error for nil XML")
	}
}

func TestParseXMPXML_NoDescription(t *testing.T) {
	xml := `<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  </rdf:RDF>
</x:xmpmeta>`
	result, err := xmp.ParseXMPXML([]byte(xml))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(result) != 0 {
		t.Errorf("expected empty map, got %v", result)
	}
}

// buildTestJPEGWithXMP wraps XMP XML into a minimal JPEG with APP1 marker
func buildTestJPEGWithXMP(xmpXML string) []byte {
	soi := []byte{0xFF, 0xD8} // SOI
	app1Content := append([]byte("Exif\x00\x00"), []byte(xmpXML)...)
	app1Length := len(app1Content) + 2 // length field includes itself
	app1 := append([]byte{0xFF, 0xE1, byte(app1Length >> 8), byte(app1Length & 0xFF)}, app1Content...)
	eoi := []byte{0xFF, 0xD9} // EOI

	result := make([]byte, 0, len(soi)+len(app1)+len(eoi))
	result = append(result, soi...)
	result = append(result, app1...)
	result = append(result, eoi...)
	return result
}
