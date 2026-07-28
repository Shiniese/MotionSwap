package extractor

import "MotionSwap/pkg/mp4"

type UnknownProcessor struct{}

func (p *UnknownProcessor) Brand() MotionPhotoBrand { return BrandUnknown }

func (p *UnknownProcessor) CanProcess(xmpInfo map[string]string) bool {
	return false
}

func (p *UnknownProcessor) CanProcessByFTYP(data []byte) bool {
	info, err := mp4.FindFTYPBox(data)
	return err == nil && info != nil
}

func (p *UnknownProcessor) Process(data []byte, xmpInfo map[string]string) (*ProcessResult, error) {
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
