package extractor

type XiaomiProcessor struct{}

func (p *XiaomiProcessor) Brand() MotionPhotoBrand { return BrandXiaomi }

func (p *XiaomiProcessor) CanProcess(xmpInfo map[string]string) bool {
	_, ok := xmpInfo["GCamera:MicroVideoOffset"]
	return ok
}

func (p *XiaomiProcessor) Process(data []byte, xmpInfo map[string]string) (*ProcessResult, error) {
	offsetStr := xmpInfo["GCamera:MicroVideoOffset"]
	offset := 0
	for _, c := range offsetStr {
		if c >= '0' && c <= '9' {
			offset = offset*10 + int(c-'0')
		} else {
			break
		}
	}
	if offset <= 0 || offset > len(data) {
		return nil, nil
	}
	videoStart := len(data) - offset
	if videoStart < 0 {
		return nil, nil
	}

	imageData := data[:videoStart:videoStart]
	videoData := data[videoStart:]

	var ts *int64
	if tsStr, ok := xmpInfo["GCamera:MicroVideoPresentationTimestampUs"]; ok {
		var v int64
		for _, c := range tsStr {
			if c >= '0' && c <= '9' {
				v = v*10 + int64(c-'0')
			}
		}
		if tsStr != "" {
			ts = &v
		}
	}

	return &ProcessResult{
		ImageData:              imageData,
		VideoData:              videoData,
		PresentationTimestampUs: ts,
		VideoOffset:            videoStart,
	}, nil
}
