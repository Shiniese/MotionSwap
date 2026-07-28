package extractor

import "MotionSwap/pkg/mp4"

type HuaweiProcessor struct{}

func (p *HuaweiProcessor) Brand() MotionPhotoBrand { return BrandHuawei }

func (p *HuaweiProcessor) CanProcess(xmpInfo map[string]string) bool {
	make, ok := xmpInfo["Make"]
	return ok && make == "HUAWEI"
}

func (p *HuaweiProcessor) CanProcessByFTYP(data []byte) bool {
	info, err := mp4.FindFTYPBox(data)
	return err == nil && info != nil
}

func (p *HuaweiProcessor) Process(data []byte, xmpInfo map[string]string) (*ProcessResult, error) {
	info, err := mp4.FindFTYPBox(data)
	if err != nil || info == nil {
		return nil, nil
	}

	videoStart := info.Offset
	if videoStart < 0 || videoStart >= len(data) {
		return nil, nil
	}

	imageData := data[:videoStart:videoStart]
	videoData := data[videoStart:]

	return &ProcessResult{
		ImageData:              imageData,
		VideoData:              videoData,
		PresentationTimestampUs: nil,
		VideoOffset:            videoStart,
	}, nil
}
