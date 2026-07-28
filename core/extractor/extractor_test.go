package extractor_test

import (
	"testing"

	"MotionSwap/core/extractor"
)

func TestXiaomiDetection(t *testing.T) {
	factory := extractor.NewProcessorFactory()
	xmpInfo := map[string]string{
		"GCamera:MicroVideo":                  "1",
		"GCamera:MicroVideoOffset":            "1234567",
		"GCamera:MicroVideoPresentationTimestampUs": "500000",
	}
	p := factory.Detect(xmpInfo, nil)
	if p == nil {
		t.Fatal("expected processor for Xiaomi XMP")
	}
	if p.Brand() != extractor.BrandXiaomi {
		t.Errorf("brand = %v, want Xiaomi", p.Brand())
	}
}

func TestAndroidGCameraDetection(t *testing.T) {
	factory := extractor.NewProcessorFactory()
	xmpInfo := map[string]string{
		"GCamera:MotionPhoto":                "1",
		"GContainer:ItemLength":              "0, 1234567",
		"GCamera:MotionPhotoPresentationTimestampUs": "500000",
	}
	p := factory.Detect(xmpInfo, nil)
	if p == nil {
		t.Fatal("expected processor for Android XMP")
	}
	if p.Brand() != extractor.BrandAndroid {
		t.Errorf("brand = %v, want Android", p.Brand())
	}
}

func TestHuaweiEXIFDetection(t *testing.T) {
	factory := extractor.NewProcessorFactory()
	xmpInfo := map[string]string{
		"Make": "HUAWEI",
	}
	p := factory.Detect(xmpInfo, nil)
	if p == nil {
		t.Fatal("expected processor for Huawei EXIF")
	}
	if p.Brand() != extractor.BrandHuawei {
		t.Errorf("brand = %v, want Huawei", p.Brand())
	}
}

func TestUnknownDetection(t *testing.T) {
	factory := extractor.NewProcessorFactory()
	mp4Data := buildFTYPData()
	p := factory.Detect(nil, mp4Data)
	if p == nil {
		t.Fatal("expected processor for unknown format with MP4 data")
	}
	if p.Brand() != extractor.BrandHuawei {
		t.Errorf("brand = %v, want Huawei (first ftyp-based fallback)", p.Brand())
	}
}

func TestNoDetection(t *testing.T) {
	factory := extractor.NewProcessorFactory()
	xmpInfo := map[string]string{
		"Something:Else": "1",
	}
	p := factory.Detect(xmpInfo, nil)
	if p != nil {
		t.Fatal("expected nil for non-motion photo")
	}
}

func buildFTYPData() []byte {
	return []byte{
		0x00, 0x00, 0x00, 0x18, // box size: 24
		'f', 't', 'y', 'p', // box type
		'm', 'p', '4', '2', // major brand
		0x00, 0x00, 0x00, 0x00, // minor version
		'i', 's', 'o', 'm', // compatible brand
		'm', 'p', '4', '2', // compatible brand
	}
}
