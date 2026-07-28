package merger

import (
	"fmt"

	"MotionSwap/pkg/xmp"
)

func mergeXiaomi(jpegData, mp4Data []byte) ([]byte, error) {
	fields := map[string]string{
		"GCamera:MicroVideo":                         "1",
		"GCamera:MicroVideoVersion":                  "1",
		"GCamera:MicroVideoOffset":                   fmt.Sprintf("%d", len(mp4Data)),
		"GCamera:MicroVideoPresentationTimestampUs":  "500000",
	}

	result, err := xmp.InjectXMP(jpegData, fields, len(mp4Data))
	if err != nil {
		return nil, fmt.Errorf("inject XMP: %w", err)
	}

	result = append(result, mp4Data...)
	return result, nil
}

func mergeAndroid(jpegData, mp4Data []byte) ([]byte, error) {
	fields := map[string]string{
		"GCamera:MotionPhoto":                        "1",
		"GCamera:MotionPhotoVersion":                 "1",
		"GCamera:MotionPhotoPresentationTimestampUs": "500000",
		"GContainer:ItemLength":                      "0, " + fmt.Sprintf("%d", len(mp4Data)),
	}

	result, err := xmp.InjectXMP(jpegData, fields, len(mp4Data))
	if err != nil {
		return nil, fmt.Errorf("inject XMP: %w", err)
	}

	result = append(result, mp4Data...)
	return result, nil
}
