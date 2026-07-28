package merger

import (
	"errors"
	"fmt"
	"os"
)

type MotionPhotoBrand int

const (
	BrandXiaomi  MotionPhotoBrand = iota + 1
	BrandAndroid
	BrandHuawei
)

type Merger struct{}

func NewMerger() *Merger {
	return &Merger{}
}

func (m *Merger) Merge(jpegData, mp4Data []byte, brand MotionPhotoBrand) ([]byte, error) {
	if len(jpegData) < 2 || jpegData[0] != 0xFF || jpegData[1] != 0xD8 {
		return nil, errors.New("invalid JPEG data")
	}
	if len(mp4Data) == 0 {
		return nil, errors.New("empty video data")
	}

	switch brand {
	case BrandXiaomi:
		return mergeXiaomi(jpegData, mp4Data)
	case BrandAndroid:
		return mergeAndroid(jpegData, mp4Data)
	case BrandHuawei:
		return mergeHuawei(jpegData, mp4Data)
	default:
		return nil, fmt.Errorf("unsupported brand: %d", brand)
	}
}

func (m *Merger) MergeFiles(jpegPath, mp4Path string, brand MotionPhotoBrand) ([]byte, error) {
	jpegData, err := os.ReadFile(jpegPath)
	if err != nil {
		return nil, fmt.Errorf("read JPEG: %w", err)
	}
	mp4Data, err := os.ReadFile(mp4Path)
	if err != nil {
		return nil, fmt.Errorf("read MP4: %w", err)
	}
	return m.Merge(jpegData, mp4Data, brand)
}

func mergeHuawei(jpegData, mp4Data []byte) ([]byte, error) {
	result := make([]byte, 0, len(jpegData)+len(mp4Data))
	result = append(result, jpegData...)
	result = append(result, mp4Data...)
	return result, nil
}
