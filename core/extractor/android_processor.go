package extractor

import (
	"strconv"
	"strings"
)

type AndroidProcessor struct{}

func (p *AndroidProcessor) Brand() MotionPhotoBrand { return BrandAndroid }

func (p *AndroidProcessor) CanProcess(xmpInfo map[string]string) bool {
	_, hasGContainer := xmpInfo["GContainer:ItemLength"]
	_, hasGCamera := xmpInfo["GCamera:MotionPhoto"]
	return hasGContainer || hasGCamera
}

func (p *AndroidProcessor) Process(data []byte, xmpInfo map[string]string) (*ProcessResult, error) {
	if itemLen, ok := xmpInfo["GContainer:ItemLength"]; ok {
		return p.processGContainer(data, xmpInfo, itemLen)
	}
	if _, ok := xmpInfo["GCamera:MotionPhoto"]; ok {
		return p.processGCamera(data, xmpInfo)
	}
	return nil, nil
}

func (p *AndroidProcessor) processGContainer(data []byte, xmpInfo map[string]string, itemLen string) (*ProcessResult, error) {
	lengths := splitLengths(itemLen)
	if len(lengths) < 2 {
		return p.processVideoAtEnd(data, xmpInfo, extractLastNumber(itemLen))
	}
	videoLen := lengths[len(lengths)-1]
	if videoLen <= 0 {
		return nil, nil
	}
	videoStart := len(data) - videoLen
	if videoStart < 0 {
		return nil, nil
	}

	imageData := data[:videoStart:videoStart]
	videoData := data[videoStart:]

	ts := extractTimestamp(xmpInfo, "GCamera:MotionPhotoPresentationTimestampUs")

	return &ProcessResult{
		ImageData:              imageData,
		VideoData:              videoData,
		PresentationTimestampUs: ts,
		VideoOffset:            videoStart,
	}, nil
}

func (p *AndroidProcessor) processGCamera(data []byte, xmpInfo map[string]string) (*ProcessResult, error) {
	if itemLen, ok := xmpInfo["GContainer:ItemLength"]; ok {
		return p.processGContainer(data, xmpInfo, itemLen)
	}
	lastNum := extractLastNumber(xmpInfo["GContainer:ItemLength"])
	if lastNum <= 0 {
		return nil, nil
	}
	return p.processVideoAtEnd(data, xmpInfo, lastNum)
}

func (p *AndroidProcessor) processVideoAtEnd(data []byte, xmpInfo map[string]string, videoLen int) (*ProcessResult, error) {
	if videoLen <= 0 || videoLen > len(data) {
		return nil, nil
	}
	videoStart := len(data) - videoLen
	if videoStart < 0 {
		return nil, nil
	}

	imageData := data[:videoStart:videoStart]
	videoData := data[videoStart:]

	ts := extractTimestamp(xmpInfo, "GCamera:MotionPhotoPresentationTimestampUs")

	return &ProcessResult{
		ImageData:              imageData,
		VideoData:              videoData,
		PresentationTimestampUs: ts,
		VideoOffset:            videoStart,
	}, nil
}

func splitLengths(s string) []int {
	parts := strings.Split(s, ",")
	var result []int
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		v, err := strconv.Atoi(part)
		if err == nil {
			result = append(result, v)
		}
	}
	return result
}
